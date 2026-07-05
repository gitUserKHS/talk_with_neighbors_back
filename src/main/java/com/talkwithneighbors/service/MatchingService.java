package com.talkwithneighbors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import com.talkwithneighbors.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {
    private static final String NOTIFICATION_TYPE_MATCH_OFFERED = "MATCH_OFFERED";
    private static final String NOTIFICATION_TYPE_MATCH_ACCEPTED_BY_OTHER = "MATCH_ACCEPTED_BY_OTHER";
    private static final String NOTIFICATION_TYPE_MATCH_REJECTED_BY_OTHER = "MATCH_REJECTED_BY_OTHER";
    private static final String NOTIFICATION_TYPE_MATCH_COMPLETED_AND_CHAT_CREATED = "MATCH_COMPLETED_AND_CHAT_CREATED";
    private static final Set<MatchStatus> ACTIVE_MATCH_STATUSES = Set.of(
            MatchStatus.PENDING,
            MatchStatus.USER1_ACCEPTED,
            MatchStatus.USER2_ACCEPTED,
            MatchStatus.BOTH_ACCEPTED
    );

    private final UserRepository userRepository;
    private final MatchRepository matchRepository;
    private final MatchingPreferencesRepository matchingPreferencesRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisSessionService redisSessionService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final OfflineNotificationService offlineNotificationService;
    private final CompatibilityScoreService compatibilityScoreService;

    @PostConstruct
    @Transactional
    public void deleteExpiredMatchesOnStartup() {
        matchRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }

    @Transactional
    public void saveMatchingPreferences(MatchingPreferencesDto preferences, Long userId) {
        User user = getUserById(userId);
        requireCompleteProfile(user, "프로필을 먼저 완성해야 매칭 설정을 저장할 수 있어요.");

        MatchingPreferences matchingPreferences = matchingPreferencesRepository
                .findByUserId(userId)
                .orElseGet(() -> {
                    MatchingPreferences created = new MatchingPreferences();
                    created.setUser(user);
                    return created;
                });

        applyPreferences(matchingPreferences, preferences, user);
        matchingPreferencesRepository.save(matchingPreferences);
    }

    @Transactional
    public List<MatchProfileDto> startMatching(MatchingPreferencesDto preferences, Long userId) {
        User currentUser = getUserById(userId);
        requireCompleteProfile(currentUser, "프로필을 먼저 완성해야 매칭 기능을 이용할 수 있어요.");

        stopMatching(userId);
        saveMatchingPreferences(preferences, userId);

        List<ScoredUser> candidates = findRecommendedCandidates(currentUser, preferences);
        List<Match> matches = candidates.stream()
                .map(candidate -> createPendingMatch(currentUser, candidate.user()))
                .collect(Collectors.toList());

        List<Match> savedMatches = matchRepository.saveAll(matches);
        List<MatchProfileDto> result = new ArrayList<>();

        for (int index = 0; index < savedMatches.size(); index++) {
            Match match = savedMatches.get(index);
            ScoredUser scoredUser = candidates.get(index);
            MatchProfileDto profileForRequester = toMatchProfile(
                    scoredUser.user(),
                    currentUser,
                    scoredUser.distance(),
                    match.getId(),
                    preferences
            );
            result.add(profileForRequester);
            sendMatchOfferNotification(match, currentUser, scoredUser.user(), preferences);
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<MatchProfileDto> getRecommendations(Long userId) {
        User currentUser = getUserById(userId);
        requireCompleteProfile(currentUser, "프로필을 먼저 완성해야 추천을 볼 수 있어요.");
        MatchingPreferencesDto preferences = matchingPreferencesRepository.findByUserId(userId)
                .map(preference -> compatibilityScoreService.toDto(preference, currentUser))
                .orElseGet(() -> compatibilityScoreService.toDto(null, currentUser));

        return findRecommendedCandidates(currentUser, preferences).stream()
                .map(candidate -> toMatchProfile(
                        candidate.user(),
                        currentUser,
                        candidate.distance(),
                        null,
                        preferences
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public MatchProfileDto requestMatch(Long requesterId, Long targetUserId) {
        User requester = getUserById(requesterId);
        User target = getUserById(targetUserId);
        requireCompleteProfile(requester, "프로필을 먼저 완성해야 매칭 요청을 보낼 수 있어요.");

        if (requesterId.equals(targetUserId)) {
            throw new MatchingException("자기 자신에게는 매칭 요청을 보낼 수 없어요.", HttpStatus.BAD_REQUEST);
        }
        if (!target.isProfileComplete()) {
            throw new MatchingException("상대방 프로필이 아직 완성되지 않았어요.", HttpStatus.BAD_REQUEST);
        }
        if (hasOneOnOneChatRoom(requesterId, targetUserId)) {
            throw new MatchingException("이미 1:1 채팅방이 있는 사용자예요.", HttpStatus.CONFLICT);
        }
        if (hasActiveMatchBetween(requesterId, targetUserId)) {
            throw new MatchingException("이미 진행 중인 매칭 요청이 있어요.", HttpStatus.CONFLICT);
        }

        MatchingPreferencesDto preferences = matchingPreferencesRepository.findByUserId(requesterId)
                .map(preference -> compatibilityScoreService.toDto(preference, requester))
                .orElseGet(() -> compatibilityScoreService.toDto(null, requester));

        Match savedMatch = matchRepository.save(createPendingMatch(requester, target));
        sendMatchOfferNotification(savedMatch, requester, target, preferences);
        return toMatchProfile(target, requester, compatibilityScoreService.calculateDistance(requester, target), savedMatch.getId(), preferences);
    }

    @Transactional
    public void processPendingMatches(Long userId) {
        if (redisSessionService == null) {
            return;
        }

        List<String> pendingMatchIds = redisSessionService.getPendingMatches(userId.toString());
        if (pendingMatchIds == null || pendingMatchIds.isEmpty()) {
            return;
        }

        for (String matchId : pendingMatchIds) {
            matchRepository.findById(matchId).ifPresent(match -> {
                if (match.getStatus() != MatchStatus.PENDING || isExpired(match)) {
                    return;
                }
                User receivingUser = match.getUser1().getId().equals(userId) ? match.getUser1() : match.getUser2();
                User offeringUser = match.getUser1().getId().equals(userId) ? match.getUser2() : match.getUser1();
                sendMatchOfferNotification(match, offeringUser, receivingUser, null);
            });
        }
    }

    @Transactional
    public void stopMatching(Long userId) {
        matchRepository.bulkExpireMatches(
                MatchStatus.EXPIRED,
                LocalDateTime.now(),
                userId,
                MatchStatus.PENDING
        );
    }

    @Transactional
    public void acceptMatch(String matchId, Long userId) {
        Match match = getMatchForUser(matchId, userId);
        ensureMatchCanBeResponded(match);

        boolean currentUserIsUser1 = match.getUser1().getId().equals(userId);
        User currentUser = currentUserIsUser1 ? match.getUser1() : match.getUser2();
        User otherUser = currentUserIsUser1 ? match.getUser2() : match.getUser1();

        MatchStatus nextStatus = nextAcceptedStatus(match.getStatus(), currentUserIsUser1);
        match.setStatus(nextStatus);
        match.setRespondedAt(LocalDateTime.now());
        Match savedMatch = matchRepository.save(match);

        notifyMatchAccepted(savedMatch, currentUser, otherUser);

        if (savedMatch.getStatus() == MatchStatus.BOTH_ACCEPTED) {
            createOrReuseChatRoom(savedMatch, currentUser, otherUser);
        }
    }

    @Transactional
    public void rejectMatch(String matchId, Long userId) {
        Match match = getMatchForUser(matchId, userId);
        ensureMatchCanBeResponded(match);

        User currentUser = match.getUser1().getId().equals(userId) ? match.getUser1() : match.getUser2();
        User otherUser = match.getUser1().getId().equals(userId) ? match.getUser2() : match.getUser1();

        match.setStatus(MatchStatus.REJECTED);
        match.setRespondedAt(LocalDateTime.now());
        Match savedMatch = matchRepository.save(match);

        notifyMatchRejected(savedMatch, currentUser, otherUser);
    }

    @Transactional(readOnly = true)
    public List<MatchProfileDto> searchNearbyUsers(Double latitude, Double longitude, Double radius, Long userId) {
        if (latitude == null || longitude == null || radius == null) {
            throw new MatchingException("위치 정보가 올바르지 않아요.", HttpStatus.BAD_REQUEST);
        }

        User currentUser = getUserById(userId);
        return userRepository.findNearbyUsers(latitude, longitude, radius).stream()
                .filter(user -> !user.getId().equals(userId))
                .map(user -> {
                    Double distance = compatibilityScoreService.calculateDistance(
                            latitude,
                            longitude,
                            user.getLatitude(),
                            user.getLongitude()
                    );
                    return toMatchProfile(user, currentUser, distance, null, null);
                })
                .collect(Collectors.toList());
    }

    public List<MatchProfileDto> searchNearbyUsers(User user, double radius) {
        return searchNearbyUsers(user.getLatitude(), user.getLongitude(), radius, user.getId());
    }

    private List<ScoredUser> findRecommendedCandidates(User currentUser, MatchingPreferencesDto preferences) {
        Double radius = preferences != null && preferences.getMaxDistance() != null ? preferences.getMaxDistance() : 50.0;
        List<User> nearbyUsers = currentUser.getLatitude() == null || currentUser.getLongitude() == null
                ? userRepository.findAll()
                : userRepository.findNearbyUsers(currentUser.getLatitude(), currentUser.getLongitude(), radius);

        List<Long> existingChatPartnerIds = chatService != null
                ? safeList(chatService.getUsersWithOneOnOneChatRooms(currentUser.getId()))
                : List.of();

        return nearbyUsers.stream()
                .map(candidate -> new ScoredUser(candidate, compatibilityScoreService.calculateDistance(currentUser, candidate)))
                .filter(candidate -> compatibilityScoreService.isEligible(currentUser, candidate.user(), preferences, candidate.distance()))
                .filter(candidate -> !existingChatPartnerIds.contains(candidate.user().getId()))
                .filter(candidate -> !hasActiveMatchBetween(currentUser.getId(), candidate.user().getId()))
                .sorted(Comparator
                        .comparingInt((ScoredUser candidate) -> compatibilityScoreService.calculateScore(
                                currentUser,
                                candidate.user(),
                                preferences,
                                candidate.distance()
                        )).reversed()
                        .thenComparing(candidate -> candidate.user().getUsername(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(20)
                .collect(Collectors.toList());
    }

    private MatchProfileDto toMatchProfile(
            User candidate,
            User currentUser,
            Double distance,
            String matchId,
            MatchingPreferencesDto preferences
    ) {
        return MatchProfileDto.fromUser(
                candidate,
                distance,
                matchId,
                compatibilityScoreService.calculateScore(currentUser, candidate, preferences, distance),
                compatibilityScoreService.sharedInterests(currentUser, candidate)
        );
    }

    private Match createPendingMatch(User requester, User target) {
        Match match = new Match();
        match.setUser1(requester);
        match.setUser2(target);
        match.setStatus(MatchStatus.PENDING);
        match.setCreatedAt(LocalDateTime.now());
        match.setExpiresAt(LocalDateTime.now().plusHours(24));
        return match;
    }

    private void applyPreferences(MatchingPreferences matchingPreferences, MatchingPreferencesDto preferences, User user) {
        MatchingPreferencesDto safePreferences = preferences != null ? preferences : compatibilityScoreService.toDto(null, user);
        Integer[] ageRange = safePreferences.getAgeRange();
        matchingPreferences.setMaxDistance(safePreferences.getMaxDistance() != null ? safePreferences.getMaxDistance() : 50.0);
        matchingPreferences.setMinAge(ageRange != null && ageRange.length > 0 && ageRange[0] != null ? ageRange[0] : 18);
        matchingPreferences.setMaxAge(ageRange != null && ageRange.length > 1 && ageRange[1] != null ? ageRange[1] : 99);
        matchingPreferences.setPreferredGender(safePreferences.getGender());
        matchingPreferences.setPreferredInterests(
                safePreferences.getInterests() != null ? safePreferences.getInterests() : safeList(user.getInterests())
        );
    }

    private MatchStatus nextAcceptedStatus(MatchStatus status, boolean currentUserIsUser1) {
        if (status == MatchStatus.PENDING) {
            return currentUserIsUser1 ? MatchStatus.USER1_ACCEPTED : MatchStatus.USER2_ACCEPTED;
        }
        if (status == MatchStatus.USER1_ACCEPTED && !currentUserIsUser1) {
            return MatchStatus.BOTH_ACCEPTED;
        }
        if (status == MatchStatus.USER2_ACCEPTED && currentUserIsUser1) {
            return MatchStatus.BOTH_ACCEPTED;
        }
        throw new MatchingException("이미 수락한 매칭이에요.", HttpStatus.CONFLICT);
    }

    private void ensureMatchCanBeResponded(Match match) {
        if (match.getStatus() == MatchStatus.BOTH_ACCEPTED
                || match.getStatus() == MatchStatus.REJECTED
                || match.getStatus() == MatchStatus.EXPIRED) {
            throw new MatchingException("이미 처리되었거나 만료된 매칭이에요.", HttpStatus.CONFLICT);
        }
        if (isExpired(match)) {
            match.setStatus(MatchStatus.EXPIRED);
            matchRepository.save(match);
            throw new MatchingException("만료된 매칭이에요.", HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isExpired(Match match) {
        return match.getExpiresAt() != null && LocalDateTime.now().isAfter(match.getExpiresAt());
    }

    private boolean hasActiveMatchBetween(Long user1Id, Long user2Id) {
        return matchRepository.existsActiveMatchBetween(user1Id, user2Id, ACTIVE_MATCH_STATUSES);
    }

    private boolean hasOneOnOneChatRoom(Long user1Id, Long user2Id) {
        return chatService != null && chatService.findOneOnOneChatRoom(user1Id, user2Id) != null;
    }

    private void createOrReuseChatRoom(Match match, User currentUser, User otherUser) {
        if (chatService == null) {
            return;
        }

        ChatRoomDto chatRoom = chatService.findOneOnOneChatRoom(currentUser.getId(), otherUser.getId());
        if (chatRoom == null) {
            String roomName = currentUser.getUsername() + "님과 " + otherUser.getUsername() + "님의 대화";
            chatRoom = chatService.createRoom(
                    roomName,
                    ChatRoomType.ONE_ON_ONE,
                    currentUser.getId().toString(),
                    List.of(otherUser.getUsername())
            );
        }

        WebSocketNotification<ChatRoomDto> notification = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_COMPLETED_AND_CHAT_CREATED,
                chatRoom,
                "매칭이 성사되어 채팅방이 열렸어요.",
                "/chat/" + chatRoom.getId()
        );
        sendToUser(currentUser.getId(), notification);
        sendToUser(otherUser.getId(), notification);
    }

    private void sendMatchOfferNotification(Match match, User requester, User target, MatchingPreferencesDto preferences) {
        MatchProfileDto requesterProfile = toMatchProfile(
                requester,
                target,
                compatibilityScoreService.calculateDistance(requester, target),
                match.getId(),
                preferences
        );
        WebSocketNotification<MatchProfileDto> notification = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_OFFERED,
                requesterProfile,
                requester.getUsername() + "님이 매칭 요청을 보냈어요."
        );

        boolean delivered = sendToUser(target.getId(), notification);
        if (!delivered) {
            saveOfflineNotification(
                    target.getId(),
                    OfflineNotification.NotificationType.MATCH_REQUEST,
                    requesterProfile,
                    requester.getUsername() + "님이 매칭 요청을 보냈어요.",
                    null,
                    10
            );
            if (redisSessionService != null) {
                redisSessionService.savePendingMatch(target.getId().toString(), match.getId());
            }
        }
    }

    private void notifyMatchAccepted(Match match, User currentUser, User otherUser) {
        Map<String, Object> data = Map.of(
                "matchId", match.getId(),
                "acceptorUserId", currentUser.getId(),
                "acceptorUsername", currentUser.getUsername(),
                "status", match.getStatus().name()
        );
        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_ACCEPTED_BY_OTHER,
                data,
                currentUser.getUsername() + "님이 매칭을 수락했어요."
        );
        if (!sendToUser(otherUser.getId(), notification)) {
            saveOfflineNotification(
                    otherUser.getId(),
                    OfflineNotification.NotificationType.MATCH_ACCEPTED,
                    data,
                    currentUser.getUsername() + "님이 매칭을 수락했어요.",
                    null,
                    10
            );
        }
    }

    private void notifyMatchRejected(Match match, User currentUser, User otherUser) {
        Map<String, Object> data = Map.of(
                "matchId", match.getId(),
                "rejectorUserId", currentUser.getId(),
                "rejectorUsername", currentUser.getUsername()
        );
        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                NOTIFICATION_TYPE_MATCH_REJECTED_BY_OTHER,
                data,
                currentUser.getUsername() + "님이 매칭을 거절했어요."
        );
        if (!sendToUser(otherUser.getId(), notification)) {
            saveOfflineNotification(
                    otherUser.getId(),
                    OfflineNotification.NotificationType.MATCH_REJECTED,
                    data,
                    currentUser.getUsername() + "님이 매칭을 거절했어요.",
                    null,
                    8
            );
        }
    }

    private boolean sendToUser(Long userId, WebSocketNotification<?> notification) {
        if (messagingTemplate == null || userId == null) {
            return false;
        }
        boolean online = redisSessionService == null || redisSessionService.isUserOnline(userId.toString());
        if (!online) {
            return false;
        }
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/match-notifications", notification);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send match notification to user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private void saveOfflineNotification(
            Long userId,
            OfflineNotification.NotificationType type,
            Object data,
            String message,
            String actionUrl,
            Integer priority
    ) {
        if (offlineNotificationService == null || objectMapper == null) {
            return;
        }
        try {
            offlineNotificationService.saveOfflineNotification(
                    userId,
                    type,
                    objectMapper.writeValueAsString(data),
                    message,
                    actionUrl,
                    priority
            );
        } catch (Exception e) {
            log.warn("Failed to save offline notification for user {}: {}", userId, e.getMessage());
        }
    }

    private Match getMatchForUser(String matchId, Long userId) {
        return matchRepository.findByIdAndUserId(matchId, userId)
                .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없어요.", HttpStatus.NOT_FOUND));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MatchingException("사용자를 찾을 수 없어요.", HttpStatus.NOT_FOUND));
    }

    private void requireCompleteProfile(User user, String message) {
        if (user == null || !user.isProfileComplete()) {
            throw new MatchingException(message, HttpStatus.BAD_REQUEST);
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ScoredUser(User user, Double distance) {
    }
}
