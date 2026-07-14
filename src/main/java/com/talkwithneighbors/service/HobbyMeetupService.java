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
import com.talkwithneighbors.entity.MeetupWaitlistEntry;
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
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;

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
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (scheduledAtUtc != null && scheduledAtUtc.isBefore(nowUtc)) {
            throw new ChatException("모임 일정은 현재 이후여야 해요.", HttpStatus.BAD_REQUEST);
        }
        if (registrationDeadlineUtc != null && scheduledAtUtc != null
                && registrationDeadlineUtc.isAfter(scheduledAtUtc)) {
            throw new ChatException("신청 마감은 모임 시작 전이어야 해요.", HttpStatus.BAD_REQUEST);
        }
        room.setScheduledAt(scheduledAtUtc);
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(request.getDurationMinutes() != null ? request.getDurationMinutes() : 120);
        room.setRegistrationDeadline(registrationDeadlineUtc);
        room.setCreator(creator);
        room.getParticipants().add(creator);

        return toDto(chatRoomRepository.save(room), creator);
    }

    public HobbyMeetupDto joinMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetup(roomId);
        if (room.getCreator() != null && userBlockRepository.existsBetween(userId, room.getCreator().getId())) {
            throw new ChatException("차단 관계인 사용자의 모임에는 참여할 수 없어요.", HttpStatus.FORBIDDEN);
        }
        if (MeetupTimePolicy.isPast(
                room.getRegistrationDeadline(), room.getMeetupTimeBasis(), Instant.now())) {
            throw new ChatException("모임 신청이 마감되었어요.", HttpStatus.CONFLICT);
        }

        if (!room.getParticipants().contains(user)) {
            if (room.getMaxParticipants() != null && room.getParticipants().size() >= room.getMaxParticipants()) {
                if (!meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
                    meetupWaitlistRepository.save(new MeetupWaitlistEntry(room, user));
                }
                return toDto(room, user);
            }
            room.getParticipants().add(user);
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
        }
        return toDto(room, user);
    }

    public void leaveMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetup(roomId);
        if (meetupWaitlistRepository.existsByRoom_IdAndUser_Id(roomId, userId)) {
            meetupWaitlistRepository.deleteByRoom_IdAndUser_Id(roomId, userId);
            return;
        }
        if (!room.getParticipants().remove(user)) {
            throw new ChatException("You are not a participant in this hobby meetup.", HttpStatus.BAD_REQUEST);
        }
        meetupWaitlistRepository.findFirstByRoom_IdOrderByCreatedAtAsc(roomId).ifPresent(entry -> {
            room.getParticipants().add(entry.getUser());
            meetupWaitlistRepository.delete(entry);
            try {
                OfflineNotification notification = offlineNotificationService.saveOfflineNotification(
                        entry.getUser().getId(), OfflineNotification.NotificationType.MEETUP_WAITLIST_PROMOTED,
                        objectMapper.writeValueAsString(Map.of("roomId", room.getId(), "title", room.getName())),
                        "대기하던 모임에 자리가 나서 참여가 확정됐어.", "/meetups", 9);
                offlineNotificationService.sendPendingNotifications(entry.getUser().getId());
            } catch (Exception ignored) {
                // 승급 트랜잭션은 알림 직렬화 실패 때문에 되돌리지 않는다.
            }
        });
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

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
