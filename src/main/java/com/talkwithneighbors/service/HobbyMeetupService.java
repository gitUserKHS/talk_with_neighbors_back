package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.domain.event.MeetupJoinedEvent;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.MeetupWaitlistRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.ChatScheduleRsvpRepository;
import com.talkwithneighbors.entity.MeetupWaitlistEntry;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.OfflineNotification;

@Service
@RequiredArgsConstructor
@Transactional
public class HobbyMeetupService {
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final UserBlockRepository userBlockRepository;
    private final MeetupWaitlistRepository meetupWaitlistRepository;
    private final ChatScheduleRepository chatScheduleRepository;
    private final ChatScheduleRsvpRepository chatScheduleRsvpRepository;
    private final ChatScheduleService chatScheduleService;
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    @Transactional(readOnly = true)
    public Page<HobbyMeetupDto> findMeetups(Long currentUserId, String keyword, String interest, Pageable pageable) {
        User currentUser = getUser(currentUserId);
        String normalizedKeyword = normalize(keyword);
        String normalizedInterest = normalize(interest);
        List<Long> excludedUserIds = userBlockRepository.findExcludedUserIds(currentUserId);

        List<HobbyMeetupDto> meetups = chatRoomRepository
                .findByTypeAndPublicRoomTrueOrderByLastMessageTimeDesc(ChatRoomType.GROUP)
                .stream()
                .filter(room -> room.getCreator() == null || !excludedUserIds.contains(room.getCreator().getId()))
                .filter(room -> matches(room, normalizedKeyword, normalizedInterest))
                .map(room -> toDto(room, currentUser))
                .sorted(Comparator
                        .comparingInt((HobbyMeetupDto meetup) -> meetup.getSharedInterests().size()).reversed()
                        .thenComparing(HobbyMeetupDto::getLastMessageTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(HobbyMeetupDto::getTitle, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        int start = Math.min((int) pageable.getOffset(), meetups.size());
        int end = Math.min(start + pageable.getPageSize(), meetups.size());
        return new PageImpl<>(meetups.subList(start, end), pageable, meetups.size());
    }

    @Transactional(readOnly = true)
    public List<HobbyMeetupDto> myMeetups(Long currentUserId) {
        User currentUser = getUser(currentUserId);
        return chatRoomRepository.findByParticipantsContainingAndType(currentUser, ChatRoomType.GROUP).stream()
                .filter(ChatRoom::isPublicRoom)
                .sorted(Comparator.comparing(
                        room -> MeetupTimePolicy.toInstant(room.getScheduledAt(), room.getMeetupTimeBasis()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(room -> toDto(room, currentUser))
                .toList();
    }

    public HobbyMeetupDto createMeetup(Long creatorId, CreateHobbyMeetupRequest request) {
        User creator = getUser(creatorId);
        List<String> tags = cleanTags(request.getInterestTags());
        if (tags.isEmpty()) {
            throw new ChatException("At least one hobby tag is required.", HttpStatus.BAD_REQUEST);
        }

        ChatRoom room = new ChatRoom();
        room.setName(request.getTitle().trim());
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setDescription(trimToNull(request.getDescription()));
        room.setInterestTags(tags);
        room.setLocation(trimToNull(request.getLocation()));
        room.setLocationAddress(trimToNull(request.getLocationAddress()));
        room.setLatitude(request.getLatitude());
        room.setLongitude(request.getLongitude());
        room.setKakaoPlaceId(trimToNull(request.getKakaoPlaceId()));
        room.setMaxParticipants(request.getMaxParticipants());
        LocalDateTime scheduledAtUtc = toUtcLocalDateTime(request.getScheduledAt());
        LocalDateTime registrationDeadlineUtc = toUtcLocalDateTime(request.getRegistrationDeadline());
        validateLegacyProfileSchedule(scheduledAtUtc, registrationDeadlineUtc);
        room.setScheduledAt(scheduledAtUtc);
        room.setMeetupTimeBasis(scheduledAtUtc != null ? MeetupTimeBasis.UTC : null);
        room.setDurationMinutes(scheduledAtUtc != null
                ? (request.getDurationMinutes() != null ? request.getDurationMinutes() : 120)
                : null);
        room.setRegistrationDeadline(registrationDeadlineUtc);
        room.setReminderSentAt(null);
        room.setCreator(creator);
        room.getParticipants().add(creator);

        ChatRoom savedRoom = chatRoomRepository.save(room);
        if (scheduledAtUtc != null) {
            chatScheduleService.synchronizeLegacyProfileSchedule(savedRoom);
        }
        return toDto(savedRoom, creator);
    }

    @Transactional(readOnly = true)
    public HobbyMeetupDto getMeetup(Long currentUserId, String roomId) {
        User currentUser = getUser(currentUserId);
        ChatRoom room = getPublicMeetup(roomId);
        if (room.getCreator() != null
                && userBlockRepository.existsBetween(currentUserId, room.getCreator().getId())) {
            throw new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND);
        }
        return toDto(room, currentUser);
    }

    public HobbyMeetupDto updateMeetup(
            Long requesterId,
            String roomId,
            CreateHobbyMeetupRequest request
    ) {
        User requester = getUser(requesterId);
        ChatRoom room = requireOwnedPublicMeetupForUpdate(roomId, requesterId);
        if (request == null || request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new ChatException("모임 이름을 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        List<String> tags = cleanTags(request.getInterestTags());
        if (tags.isEmpty()) {
            throw new ChatException("관심사 태그를 하나 이상 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        Integer maxParticipants = request.getMaxParticipants();
        if (maxParticipants == null || maxParticipants < 2 || maxParticipants > 50) {
            throw new ChatException("모집 인원은 2명 이상 50명 이하로 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        if (maxParticipants < room.getParticipants().size()) {
            throw new ChatException(
                    "모집 인원은 현재 참여자 수보다 적게 줄일 수 없어.",
                    HttpStatus.CONFLICT);
        }
        if ((request.getLatitude() == null) != (request.getLongitude() == null)) {
            throw new ChatException("위도와 경도는 함께 입력해 줘.", HttpStatus.BAD_REQUEST);
        }

        // Preserve any unrepresented profile event before an old client can
        // replace the room projection with its edited date.
        chatScheduleService.materializeLegacyProfileSchedule(room);
        LocalDateTime requestedScheduledAt = toUtcLocalDateTime(request.getScheduledAt());
        LocalDateTime requestedDeadline = toUtcLocalDateTime(request.getRegistrationDeadline());
        if (requestedScheduledAt != null) {
            validateLegacyProfileSchedule(requestedScheduledAt, requestedDeadline);
        }
        Integer requestedDuration = request.getDurationMinutes() != null
                ? request.getDurationMinutes() : 120;
        if (requestedScheduledAt != null
                && (requestedDuration < 30 || requestedDuration > 1440)) {
            throw new ChatException(
                    "모임 시간은 30분 이상 1,440분 이하로 입력해 줘.",
                    HttpStatus.BAD_REQUEST);
        }
        LocalDateTime previousScheduledAt = room.getScheduledAt();
        Instant previousProjectionStart = MeetupTimePolicy.toInstant(
                room.getScheduledAt(), room.getMeetupTimeBasis());
        Integer previousProjectionDuration = room.getDurationMinutes();

        room.setName(request.getTitle().trim());
        room.setDescription(trimToNull(request.getDescription()));
        room.setInterestTags(tags);
        room.setLocation(trimToNull(request.getLocation()));
        room.setLocationAddress(trimToNull(request.getLocationAddress()));
        room.setLatitude(request.getLatitude());
        room.setLongitude(request.getLongitude());
        room.setKakaoPlaceId(trimToNull(request.getKakaoPlaceId()));
        room.setMaxParticipants(maxParticipants);
        if (requestedScheduledAt != null) {
            room.setScheduledAt(requestedScheduledAt);
            room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
            room.setDurationMinutes(requestedDuration);
            if (!Objects.equals(previousScheduledAt, requestedScheduledAt)) {
                room.setReminderSentAt(null);
            }
        }
        if (requestedDeadline != null) {
            room.setRegistrationDeadline(requestedDeadline);
        }
        if (requestedScheduledAt != null) {
            chatScheduleService.synchronizeLegacyProfileSchedule(
                    room, previousProjectionStart, previousProjectionDuration);
        }
        promoteWaitlistedUsers(room);
        return toDto(chatRoomRepository.save(room), requester);
    }

    public void deleteMeetup(Long requesterId, String roomId) {
        requireOwnedPublicMeetupForUpdate(roomId, requesterId);
        chatService.deleteRoom(roomId, requesterId);
    }

    public HobbyMeetupDto joinMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetupForUpdate(roomId);
        if (room.getCreator() != null && userBlockRepository.existsBetween(userId, room.getCreator().getId())) {
            throw new ChatException("차단 관계인 사용자의 모임에는 참여할 수 없어요.", HttpStatus.FORBIDDEN);
        }
        if (!chatScheduleRepository.existsByRoom_Id(room.getId()) && MeetupTimePolicy.isPast(
                room.getRegistrationDeadline(), room.getMeetupTimeBasis(), Instant.now())) {
            throw new ChatException("모임 신청이 마감되었어요.", HttpStatus.CONFLICT);
        }

        boolean promoted = promoteWaitlistedUsers(room);
        if (room.getParticipants().contains(user)) {
            if (meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
                meetupWaitlistRepository.deleteByRoom_IdAndUser_Id(roomId, userId);
            }
            if (promoted) {
                chatRoomRepository.save(room);
            }
            return toDto(room, user);
        }

        if (room.getMaxParticipants() != null
                && room.getParticipants().size() >= room.getMaxParticipants()) {
            if (!meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
                meetupWaitlistRepository.save(new MeetupWaitlistEntry(room, user));
            }
            if (promoted) {
                chatRoomRepository.save(room);
            }
            return toDto(room, user);
        }

        room.getParticipants().add(user);
        if (meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
            meetupWaitlistRepository.deleteByRoom_IdAndUser_Id(roomId, userId);
        }
        chatRoomRepository.save(room);
        if (room.getCreator() == null
                || room.getCreator().getAccountType() != UserAccountType.SYSTEM) {
            domainEventPublisher.publish(MeetupJoinedEvent.create(
                    room.getId(),
                    room.getName(),
                    user.getId(),
                    room.getCreator() != null ? room.getCreator().getId() : null
            ));
        }
        return toDto(room, user);
    }

    public void leaveMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetupForUpdate(roomId);
        if (room.getCreator() != null && Objects.equals(room.getCreator().getId(), userId)) {
            throw new ChatException(
                    "모임장은 나가기 대신 모임 삭제를 이용해 줘.",
                    HttpStatus.CONFLICT);
        }
        if (chatScheduleRepository.existsByRoom_IdAndCreator_IdAndStatusAndStartsAtAfter(
                roomId, userId, ChatScheduleStatus.SCHEDULED, Instant.now())) {
            throw new ChatException(
                    "먼저 이 채팅방에서 만든 예정 일정을 취소해 줘.",
                    HttpStatus.CONFLICT);
        }
        if (meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
            meetupWaitlistRepository.deleteByRoom_IdAndUser_Id(roomId, userId);
            return;
        }
        if (!room.getParticipants().remove(user)) {
            throw new ChatException("You are not a participant in this hobby meetup.", HttpStatus.BAD_REQUEST);
        }
        chatScheduleRsvpRepository.deleteBySchedule_Room_IdAndUser_Id(roomId, userId);
        promoteWaitlistedUsers(room);
        chatRoomRepository.save(room);
    }

    private HobbyMeetupDto toDto(ChatRoom room, User currentUser) {
        HobbyMeetupDto dto = HobbyMeetupDto.fromEntity(room, currentUser);
        if (currentUser != null) {
            dto.setWaitlisted(meetupWaitlistRepository.existsByRoom_IdAndUser_Id(room.getId(), currentUser.getId()));
        }
        dto.setWaitlistCount(meetupWaitlistRepository.countByRoom_Id(room.getId()));
        return dto;
    }

    private boolean matches(ChatRoom room, String keyword, String interest) {
        if (!keyword.isEmpty()) {
            String searchable = String.join(" ", List.of(
                    Objects.toString(room.getName(), ""),
                    Objects.toString(room.getDescription(), ""),
                    Objects.toString(room.getLocation(), ""),
                    String.join(" ", room.getInterestTags() != null ? room.getInterestTags() : List.of())
            )).toLowerCase(Locale.ROOT);
            if (!searchable.contains(keyword)) {
                return false;
            }
        }
        return interest.isEmpty() || (room.getInterestTags() != null && room.getInterestTags().stream()
                .filter(Objects::nonNull)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .anyMatch(interest::equals));
    }

    private List<String> cleanTags(List<String> values) {
        Map<String, String> uniqueTags = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                String trimmed = trimToNull(value);
                if (trimmed != null) {
                    uniqueTags.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                }
            }
        }
        return new ArrayList<>(uniqueTags.values());
    }

    private ChatRoom getPublicMeetup(String roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND));
        if (room.getType() != ChatRoomType.GROUP || !room.isPublicRoom()) {
            throw new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND);
        }
        return room;
    }

    private ChatRoom requireOwnedPublicMeetupForUpdate(String roomId, Long requesterId) {
        ChatRoom room = getPublicMeetupForUpdate(roomId);
        if (room.getCreator() == null || !Objects.equals(room.getCreator().getId(), requesterId)) {
            throw new ChatException("모임장만 이 모임을 수정하거나 삭제할 수 있어.", HttpStatus.FORBIDDEN);
        }
        return room;
    }

    private ChatRoom getPublicMeetupForUpdate(String roomId) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND));
        if (room.getType() != ChatRoomType.GROUP || !room.isPublicRoom()) {
            throw new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND);
        }
        return room;
    }

    /**
     * Offers every open seat to the oldest eligible wait-list entry before a
     * new caller can take it. Callers hold the room's pessimistic write lock.
     */
    private boolean promoteWaitlistedUsers(ChatRoom room) {
        if (!chatScheduleRepository.existsByRoom_Id(room.getId()) && MeetupTimePolicy.isPast(
                room.getRegistrationDeadline(), room.getMeetupTimeBasis(), Instant.now())) {
            return false;
        }
        List<MeetupWaitlistEntry> entries =
                meetupWaitlistRepository.findByRoom_IdOrderByCreatedAtAsc(room.getId());
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        int availableSlots = room.getMaxParticipants() == null
                ? Integer.MAX_VALUE
                : Math.max(0, room.getMaxParticipants() - room.getParticipants().size());
        boolean changed = false;
        for (MeetupWaitlistEntry entry : entries) {
            User waitlistedUser = entry.getUser();
            if (waitlistedUser == null || room.getParticipants().contains(waitlistedUser)) {
                meetupWaitlistRepository.delete(entry);
                changed = true;
                continue;
            }
            if (room.getCreator() != null && userBlockRepository.existsBetween(
                    waitlistedUser.getId(), room.getCreator().getId())) {
                meetupWaitlistRepository.delete(entry);
                changed = true;
                continue;
            }
            if (availableSlots <= 0) {
                break;
            }

            room.getParticipants().add(waitlistedUser);
            meetupWaitlistRepository.delete(entry);
            notifyWaitlistPromotion(room, waitlistedUser);
            availableSlots--;
            changed = true;
        }
        return changed;
    }

    private void notifyWaitlistPromotion(ChatRoom room, User user) {
        try {
            OfflineNotification notification = offlineNotificationService.saveOfflineNotification(
                    user.getId(), OfflineNotification.NotificationType.MEETUP_WAITLIST_PROMOTED,
                    objectMapper.writeValueAsString(Map.of("roomId", room.getId(), "title", room.getName())),
                    "대기하던 모임에 자리가 나서 참여가 확정됐어.", "/meetups", 9);
            offlineNotificationService.sendPendingNotifications(user.getId());
        } catch (Exception ignored) {
            // 승급 트랜잭션은 알림 직렬화 실패 때문에 되돌리지 않는다.
        }
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found.", HttpStatus.NOT_FOUND));
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed.toLowerCase(Locale.ROOT) : "";
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private void validateLegacyProfileSchedule(
            LocalDateTime scheduledAtUtc,
            LocalDateTime registrationDeadlineUtc
    ) {
        if (scheduledAtUtc != null && scheduledAtUtc.isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ChatException("모임 일정은 현재 이후여야 해.", HttpStatus.BAD_REQUEST);
        }
        if (registrationDeadlineUtc != null && scheduledAtUtc != null
                && registrationDeadlineUtc.isAfter(scheduledAtUtc)) {
            throw new ChatException("신청 마감은 모임 시작 전이어야 해.", HttpStatus.BAD_REQUEST);
        }
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

}
