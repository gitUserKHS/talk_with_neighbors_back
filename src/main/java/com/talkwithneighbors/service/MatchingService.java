package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.MatchRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.MatchingPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이웃 매칭을 관리하는 서비스 클래스
 * 사용자 간의 매칭 생성, 수락, 거절 등의 기능을 제공합니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {

    /**
     * 사용자 정보를 관리하는 리포지토리
     */
    private final UserRepository userRepository;

    /**
     * 매칭 정보를 관리하는 리포지토리
     */
    private final MatchRepository matchRepository;

    /**
     * 매칭 선호도 정보를 관리하는 리포지토리
     */
    private final MatchingPreferencesRepository matchingPreferencesRepository;

    /**
     * WebSocket 메시지를 전송하기 위한 템플릿
     */
    private final SimpMessagingTemplate messagingTemplate;

    private final RedisSessionService redisSessionService;

    /**
     * 사용자의 매칭 선호도를 저장합니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param userId 사용자 ID
     */
    @Transactional
    public void saveMatchingPreferences(MatchingPreferencesDto preferences, Long userId) {
        User user = getUserById(userId);

        // 프로필 완성도 검사
        if (!user.isProfileComplete()) {
            throw new MatchingException("프로필을 먼저 설정해야 매칭 환경설정을 저장할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        
        // 기존 설정이 있으면 업데이트, 없으면 새로 생성
        MatchingPreferences matchingPreferences = matchingPreferencesRepository
            .findByUserId(userId)
            .orElseGet(() -> {
                MatchingPreferences p = new MatchingPreferences();
                p.setUser(user);
                return p;
            });
        
        // 엔티티 필드 업데이트
        matchingPreferences.setLatitude(preferences.getLocation().getLatitude());
        matchingPreferences.setLongitude(preferences.getLocation().getLongitude());
        matchingPreferences.setAddress(preferences.getLocation().getAddress());
        matchingPreferences.setMaxDistance(preferences.getMaxDistance());
        matchingPreferences.setMinAge(preferences.getAgeRange()[0]);
        matchingPreferences.setMaxAge(preferences.getAgeRange()[1]);
        matchingPreferences.setPreferredGender(preferences.getGender());
        matchingPreferences.setPreferredInterests(preferences.getInterests());
        matchingPreferencesRepository.save(matchingPreferences);
    }

    /**
     * 매칭을 시작하고 주변 사용자들에게 매칭 요청을 보냅니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param userId 사용자 ID
     */
    @Transactional
    public void startMatching(MatchingPreferencesDto preferences, Long userId) {
        log.info("[startMatching] userId: {}", userId);
        User user = getUserById(userId);

        // 프로필 완성도 검사
        if (!user.isProfileComplete()) {
            throw new MatchingException("프로필을 먼저 설정해야 매칭 기능을 이용할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        
        // 기존 매칭 중지
        stopMatching(userId);
        
        // 새로운 매칭 선호도 저장
        saveMatchingPreferences(preferences, userId);
        
        // 주변 사용자 검색 및 매칭 요청
        List<User> nearbyUsers = userRepository.findNearbyUsers(
            user.getLatitude(), 
            user.getLongitude(), 
            5.0 // 기본 반경 5km
        );
        
        // 배치용 매칭 엔티티 생성
        List<Match> matches = new ArrayList<>();
        for (User nearbyUser : nearbyUsers) {
            Match match = new Match();
            match.setUser1(user);
            match.setUser2(nearbyUser);
            match.setStatus(MatchStatus.PENDING);
            match.setCreatedAt(LocalDateTime.now());
            match.setExpiresAt(LocalDateTime.now().plusHours(24)); // 24시간 후 만료
            matches.add(match);
        }
        
        // 일괄 저장
        List<Match> savedMatches = matchRepository.saveAll(matches);
        
        // 저장된 매칭에 대해 알림 또는 대기 처리
        for (int i = 0; i < savedMatches.size(); i++) {
            Match match = savedMatches.get(i);
            User nearbyUser = nearbyUsers.get(i);
            if (redisSessionService.isUserOnline(nearbyUser.getId().toString())) {
                messagingTemplate.convertAndSendToUser(
                    nearbyUser.getId().toString(),
                    "/queue/matches",
                    MatchProfileDto.fromUser(user, calculateDistance(
                        user.getLatitude(), user.getLongitude(),
                        nearbyUser.getLatitude(), nearbyUser.getLongitude()
                    ))
                );
            } else {
                redisSessionService.savePendingMatch(nearbyUser.getId().toString(), match.getId().toString());
                log.info("Saved pending match for offline user: {}", nearbyUser.getId());
            }
        }
    }

    /**
     * 사용자가 온라인 상태가 되었을 때 대기 중인 매칭 요청을 처리합니다.
     */
    @Transactional
    public void processPendingMatches(Long userId) {
        List<String> pendingMatchIds = redisSessionService.getPendingMatches(userId.toString());
        
        if (pendingMatchIds != null && !pendingMatchIds.isEmpty()) {
            log.info("Processing {} pending matches for user: {}", pendingMatchIds.size(), userId);
            
            for (String matchId : pendingMatchIds) {
                Match match = matchRepository.findById(matchId)
                        .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
                
                // 만료되지 않은 매칭만 처리
                if (match.getStatus() == MatchStatus.PENDING && 
                    !LocalDateTime.now().isAfter(match.getExpiresAt())) {
                    
                    // WebSocket을 통해 매칭 요청 알림 전송
                    messagingTemplate.convertAndSendToUser(
                        userId.toString(),
                        "/queue/matches",
                        MatchProfileDto.fromUser(
                            match.getUser1().getId().equals(userId) ? match.getUser2() : match.getUser1(),
                            calculateDistance(
                                match.getUser1().getLatitude(), match.getUser1().getLongitude(),
                                match.getUser2().getLatitude(), match.getUser2().getLongitude()
                            )
                        )
                    );
                } else {
                    // 만료된 매칭은 상태 업데이트
                    match.setStatus(MatchStatus.EXPIRED);
                    match.setRespondedAt(LocalDateTime.now());
                    matchRepository.save(match);
                    log.info("[processPendingMatches] match saved: user1={}, user2={}, matchId={}",
                        match.getUser1() != null ? match.getUser1().getId() : null,
                        match.getUser2() != null ? match.getUser2().getId() : null,
                        match.getId());
                    if (match.getId() == null) {
                        log.error("[processPendingMatches] ERROR: match.getId() is null after save! match object: {}", match);
                    }
                }
            }
        }
    }

    /**
     * 매칭을 중지하고 대기 중인 매칭들을 만료 처리합니다.
     * 
     * @param userId 사용자 ID
     */
    @Transactional
    public void stopMatching(Long userId) {
        User user = getUserById(userId);
        
        // 대기 중인 매칭을 일괄 만료 처리 (bulk update)
        int expiredCount = matchRepository.bulkExpireMatches(
            MatchStatus.EXPIRED,
            LocalDateTime.now(),
            userId,
            MatchStatus.PENDING
        );
        log.info("[stopMatching] expired {} pending matches for user {}", expiredCount, userId);
    }

    /**
     * 매칭 요청을 수락합니다.
     * 
     * @param matchId 매칭 ID
     * @param userId 사용자 ID
     * @throws MatchingException 매칭이 없거나 이미 처리된 경우
     */
    @Transactional
    public void acceptMatch(String matchId, Long userId) {
        Match match = matchRepository.findByIdAndUserId(matchId, userId)
                .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (match.getStatus() != MatchStatus.PENDING) {
            throw new MatchingException("이미 처리된 매칭입니다.", HttpStatus.BAD_REQUEST);
        }

        if (LocalDateTime.now().isAfter(match.getExpiresAt())) {
            match.setStatus(MatchStatus.EXPIRED);
            matchRepository.save(match);
            throw new MatchingException("만료된 매칭입니다.", HttpStatus.BAD_REQUEST);
        }

        match.setStatus(MatchStatus.ACCEPTED);
        match.setRespondedAt(LocalDateTime.now());
        matchRepository.save(match);

        // 매칭 수락 알림 전송
        User otherUser = match.getUser1().getId().equals(userId) 
                ? match.getUser2() : match.getUser1();
        
        // 상대방이 온라인인 경우에만 알림 전송
        if (redisSessionService.isUserOnline(otherUser.getId().toString())) {
            messagingTemplate.convertAndSendToUser(
                otherUser.getId().toString(),
                "/queue/match-accepted",
                match.getId()
            );
        } else {
            // 오프라인 사용자에게는 대기 중인 알림으로 저장
            redisSessionService.savePendingMatch(otherUser.getId().toString(), match.getId().toString());
        }
    }

    /**
     * 매칭 요청을 거절합니다.
     * 
     * @param matchId 매칭 ID
     * @param userId 사용자 ID
     * @throws MatchingException 매칭이 없거나 이미 처리된 경우
     */
    @Transactional
    public void rejectMatch(String matchId, Long userId) {
        Match match = matchRepository.findByIdAndUserId(matchId, userId)
                .orElseThrow(() -> new MatchingException("매칭을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        if (match.getStatus() != MatchStatus.PENDING) {
            throw new MatchingException("이미 처리된 매칭입니다.", HttpStatus.BAD_REQUEST);
        }

        match.setStatus(MatchStatus.REJECTED);
        match.setRespondedAt(LocalDateTime.now());
        matchRepository.save(match);
    }

    /**
     * 주변 사용자들을 검색합니다.
     * 
     * @param latitude 위도
     * @param longitude 경도
     * @param radius 검색 반경 (킬로미터)
     * @param userId 현재 사용자 ID
     * @return 주변 사용자 프로필 목록
     */
    @Transactional(readOnly = true)
    public List<MatchProfileDto> searchNearbyUsers(Double latitude, Double longitude, Double radius, Long userId) {
        User user = getUserById(userId);
        
        // 사용자의 위치 정보 업데이트
        user.setLatitude(latitude);
        user.setLongitude(longitude);
        userRepository.save(user);
        
        // 주변 사용자 검색
        List<User> nearbyUsers = userRepository.findNearbyUsers(latitude, longitude, radius);
        
        return nearbyUsers.stream()
                .filter(nearbyUser -> !nearbyUser.getId().equals(userId))
                .map(nearbyUser -> MatchProfileDto.fromUser(
                    nearbyUser,
                    calculateDistance(latitude, longitude, nearbyUser.getLatitude(), nearbyUser.getLongitude())
                ))
                .collect(Collectors.toList());
    }

    /**
     * 사용자와 매칭 가능한 주변 사용자들을 찾습니다.
     * 
     * @param user 현재 사용자
     * @param preferences 매칭 선호도 정보
     * @return 매칭 가능한 사용자 목록
     */
    private List<User> findPotentialMatches(User user, MatchingPreferences preferences) {
        // 사용자의 매칭 선호도에 맞는 주변 사용자 검색
        List<User> nearbyUsers = userRepository.findNearbyUsers(
                user.getLatitude(),
                user.getLongitude(),
                preferences.getMaxDistance()
        );

        return nearbyUsers.stream()
                .filter(potentialMatch -> 
                    // 기본 필터링
                    !potentialMatch.getId().equals(user.getId()) &&
                    (preferences.getPreferredGender() == null || 
                     preferences.getPreferredGender().equals(potentialMatch.getGender())) &&
                    potentialMatch.getAge() >= preferences.getMinAge() &&
                    potentialMatch.getAge() <= preferences.getMaxAge() &&
                    (potentialMatch.getGender() == null || 
                     potentialMatch.getGender().equals(user.getGender())) &&
                    user.getAge() >= preferences.getMinAge() &&
                    user.getAge() <= preferences.getMaxAge() &&
                    // 관심사 매칭 점수 계산
                    calculateInterestScore(preferences, potentialMatch) >= 0.5
                )
                .sorted((u1, u2) -> {
                    double score1 = calculateInterestScore(preferences, u1);
                    double score2 = calculateInterestScore(preferences, u2);
                    return Double.compare(score2, score1); // 높은 점수순 정렬
                })
                .limit(10) // 상위 10명만 선택
                .collect(Collectors.toList());
    }

    /**
     * 두 사용자 간의 관심사 매칭 점수를 계산합니다.
     * Jaccard 유사도를 사용하여 계산합니다.
     * 
     * @param preferences 매칭 선호도 정보
     * @param user 사용자
     * @return 매칭 점수 (0.0 ~ 1.0)
     */
    private double calculateInterestScore(MatchingPreferences preferences, User user) {
        if (preferences.getPreferredInterests() == null || user.getInterests() == null) {
            return 0.0;
        }

        Set<String> interests1 = new HashSet<>(preferences.getPreferredInterests());
        Set<String> interests2 = new HashSet<>(user.getInterests());

        if (interests1.isEmpty() || interests2.isEmpty()) {
            return 0.0;
        }

        // Jaccard 유사도 계산
        Set<String> intersection = new HashSet<>(interests1);
        intersection.retainAll(interests2);
        Set<String> union = new HashSet<>(interests1);
        union.addAll(interests2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * ID로 사용자를 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 사용자 정보
     * @throws MatchingException 사용자를 찾을 수 없는 경우
     */
    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MatchingException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    /**
     * 두 지점 간의 거리를 계산합니다.
     * Haversine 공식을 사용하여 지구 표면의 거리를 계산합니다.
     * 
     * @param lat1 첫 번째 지점의 위도
     * @param lon1 첫 번째 지점의 경도
     * @param lat2 두 번째 지점의 위도
     * @param lon2 두 번째 지점의 경도
     * @return 두 지점 간의 거리 (킬로미터)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 지구의 반경 (km)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public List<MatchProfileDto> searchNearbyUsers(User user, double radius) {
        // 사용자의 현재 위치 저장
        user.setLastLocationUpdate(LocalDateTime.now());
        userRepository.save(user);

        // 주변 사용자 검색
        double lat = user.getLatitude();
        double lng = user.getLongitude();
        
        // 위도/경도 범위 계산 (약 111km = 1도)
        double latDelta = radius / 111.0;
        double lngDelta = radius / (111.0 * Math.cos(Math.toRadians(lat)));
        
        List<User> nearbyUsers = userRepository.findByLatitudeBetweenAndLongitudeBetween(
            lat - latDelta, lat + latDelta,
            lng - lngDelta, lng + lngDelta
        );

        // 현재 사용자 제외 및 거리 계산
        return nearbyUsers.stream()
            .filter(u -> !u.getId().equals(user.getId()))
            .map(u -> {
                double distance = calculateDistance(
                    lat, lng,
                    u.getLatitude(), u.getLongitude()
                );
                if (distance <= radius) {
                    return MatchProfileDto.fromUser(u, distance);
                }
                return null;
            })
            .filter(dto -> dto != null)
            .collect(Collectors.toList());
    }
} 