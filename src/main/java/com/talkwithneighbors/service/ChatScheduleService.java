package com.talkwithneighbors.service;

import com.talkwithneighbors.domain.event.ChatScheduleCardChangedEvent;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.schedule.CancelChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.ChatScheduleDto;
import com.talkwithneighbors.dto.schedule.CreateChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.UpdateChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.UpdateChatScheduleRsvpRequest;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleRsvp;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.ChatScheduleRsvpRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ChatScheduleService {
    private final ChatScheduleRepository scheduleRepository;
    private final ChatScheduleRsvpRepository rsvpRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ChatScheduleDto create(
            String roomId,
            Long requesterId,
            CreateChatScheduleRequest request
    ) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipantForUpdate(roomId, requester);
        requireMutationAllowed(room, requesterId);

        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(UUID.randomUUID().toString());
        schedule.setRoom(room);
        schedule.setCreator(requester);
        schedule.setTitle(requireTitle(request.title()));
        schedule.setDescription(trimToNull(request.description()));
        schedule.setStartsAt(requireFuture(request.startsAt().toInstant()));
        if (request.durationMinutes() == null) {
            throw new ChatException("일정 시간을 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        schedule.setDurationMinutes(request.durationMinutes());
        schedule.setTimeZone(request.timeZone().trim());
        applyLocation(
                schedule,
                request.location(),
                request.locationAddress(),
                request.latitude(),
                request.longitude(),
                request.kakaoPlaceId());
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        schedule.addRsvp(new ChatScheduleRsvp(
                schedule, requester, ChatScheduleRsvpStatus.ATTENDING));
        scheduleRepository.saveAndFlush(schedule);

        Message card = new Message();
        card.setId(UUID.randomUUID().toString());
        card.setChatRoom(room);
        card.setSender(requester);
        card.setContent(cardPreview(schedule));
        card.setType(Message.MessageType.SCHEDULE);
        card.setSchedule(schedule);
        card.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        card.getReadByUsers().add(requesterId);
        messageRepository.saveAndFlush(card);

        room.setLastMessage(card.getContent());
        room.setLastMessageTime(card.getCreatedAt());
        chatRoomRepository.save(room);

        publishCard(room, card);
        return ChatScheduleDto.fromEntity(schedule, requesterId);
    }

    @Transactional(readOnly = true)
    public List<ChatScheduleDto> list(String roomId, Long requesterId) {
        User requester = requireUser(requesterId);
        requireParticipant(roomId, requester);
        return scheduleRepository.findDetailedByRoomId(roomId).stream()
                .map(schedule -> ChatScheduleDto.fromEntity(schedule, requesterId))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatScheduleDto get(String roomId, String scheduleId, Long requesterId) {
        User requester = requireUser(requesterId);
        requireParticipant(roomId, requester);
        return ChatScheduleDto.fromEntity(
                requireDetailedSchedule(roomId, scheduleId), requesterId);
    }

    public ChatScheduleDto update(
            String roomId,
            String scheduleId,
            Long requesterId,
            UpdateChatScheduleRequest request
    ) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipant(roomId, requester);
        requireMutationAllowed(room, requesterId);
        ChatSchedule schedule = requireDetailedSchedule(roomId, scheduleId);
        requireCreator(schedule, requesterId);
        requireScheduled(schedule);
        requireNotStarted(schedule);
        requireVersion(schedule, request.version());

        if (request.title() != null) {
            schedule.setTitle(requireTitle(request.title()));
        }
        if (request.description() != null) {
            schedule.setDescription(trimToNull(request.description()));
        }
        if (request.startsAt() != null) {
            schedule.setStartsAt(requireFuture(request.startsAt().toInstant()));
        }
        if (request.durationMinutes() != null) {
            schedule.setDurationMinutes(request.durationMinutes());
        }
        if (request.timeZone() != null) {
            if (request.timeZone().isBlank()) {
                throw new ChatException("시간대를 비워둘 수 없어요.", HttpStatus.BAD_REQUEST);
            }
            schedule.setTimeZone(request.timeZone().trim());
        }
        // The editor submits the complete location group. An all-null group means
        // that the member intentionally removed the place from this schedule.
        applyLocation(
                schedule,
                request.location(),
                request.locationAddress(),
                request.latitude(),
                request.longitude(),
                request.kakaoPlaceId());

        flushWithConflict(schedule);
        Message card = requireCard(roomId, scheduleId);
        card.setContent(cardPreview(schedule));
        card.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageRepository.save(card);
        refreshRoomPreviewWhenCardIsLatest(room, card);
        publishCard(room, card);
        return ChatScheduleDto.fromEntity(schedule, requesterId);
    }

    public ChatScheduleDto cancel(
            String roomId,
            String scheduleId,
            Long requesterId,
            CancelChatScheduleRequest request
    ) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipant(roomId, requester);
        requireMutationAllowed(room, requesterId);
        ChatSchedule schedule = requireDetailedSchedule(roomId, scheduleId);
        requireCreator(schedule, requesterId);
        if (schedule.getStatus() == ChatScheduleStatus.CANCELLED) {
            return ChatScheduleDto.fromEntity(schedule, requesterId);
        }
        requireNotStarted(schedule);
        requireVersion(schedule, request.version());

        schedule.setStatus(ChatScheduleStatus.CANCELLED);
        schedule.setCancelledAt(Instant.now());
        flushWithConflict(schedule);
        Message card = requireCard(roomId, scheduleId);
        card.setContent(cardPreview(schedule));
        card.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageRepository.save(card);
        refreshRoomPreviewWhenCardIsLatest(room, card);
        publishCard(room, card);
        return ChatScheduleDto.fromEntity(schedule, requesterId);
    }

    public ChatScheduleDto rsvp(
            String roomId,
            String scheduleId,
            Long requesterId,
            UpdateChatScheduleRsvpRequest request
    ) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipant(roomId, requester);
        requireMutationAllowed(room, requesterId);
        ChatSchedule schedule = scheduleRepository.findLockedByIdAndRoomId(scheduleId, roomId)
                .orElseThrow(this::scheduleNotFound);
        requireScheduled(schedule);
        requireNotStarted(schedule);

        if (schedule.getCreator().getId().equals(requesterId)
                && request.status() == ChatScheduleRsvpStatus.NOT_ATTENDING) {
            throw new ChatException(
                    "일정을 만든 사람은 불참으로 변경할 수 없어. 먼저 일정을 취소해 줘.",
                    HttpStatus.CONFLICT);
        }

        var existingRsvp = rsvpRepository
                .findBySchedule_IdAndUser_Id(scheduleId, requesterId);
        if (existingRsvp.isPresent() && existingRsvp.get().getStatus() == request.status()) {
            return ChatScheduleDto.fromEntity(schedule, requesterId);
        }
        ChatScheduleRsvp rsvp = existingRsvp
                .orElseGet(() -> {
                    ChatScheduleRsvp created = new ChatScheduleRsvp(
                            schedule, requester, request.status());
                    schedule.addRsvp(created);
                    return created;
                });
        rsvp.setStatus(request.status());
        rsvpRepository.saveAndFlush(rsvp);
        // RSVP changes are also schedule-card revisions. Bumping the optimistic
        // version gives clients a monotonic ordering key for realtime upserts.
        schedule.setUpdatedAt(Instant.now());
        flushWithConflict(schedule);

        Message card = requireCard(roomId, scheduleId);
        publishCard(room, card);
        return ChatScheduleDto.fromEntity(schedule, requesterId);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("사용자를 찾을 수 없어.", HttpStatus.NOT_FOUND));
    }

    private ChatRoom requireParticipant(String roomId, User user) {
        return chatRoomRepository.findByIdAndParticipantsContaining(roomId, user)
                .orElseThrow(() -> new ChatException(
                        "채팅방을 찾을 수 없거나 참가자가 아니야.", HttpStatus.NOT_FOUND));
    }

    private ChatRoom requireParticipantForUpdate(String roomId, User user) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없어.", HttpStatus.NOT_FOUND));
        boolean participant = room.getParticipants().stream()
                .anyMatch(candidate -> candidate.getId().equals(user.getId()));
        if (!participant) {
            throw new ChatException(
                    "채팅방을 찾을 수 없거나 참가자가 아니야.", HttpStatus.NOT_FOUND);
        }
        return room;
    }

    private ChatSchedule requireDetailedSchedule(String roomId, String scheduleId) {
        return scheduleRepository.findDetailedByIdAndRoomId(scheduleId, roomId)
                .orElseThrow(this::scheduleNotFound);
    }

    private ChatException scheduleNotFound() {
        return new ChatException("일정을 찾을 수 없어.", HttpStatus.NOT_FOUND);
    }

    private void requireCreator(ChatSchedule schedule, Long requesterId) {
        if (!schedule.getCreator().getId().equals(requesterId)) {
            throw new ChatException("일정을 만든 사람만 변경하거나 취소할 수 있어.", HttpStatus.FORBIDDEN);
        }
    }

    private void requireScheduled(ChatSchedule schedule) {
        if (schedule.getStatus() != ChatScheduleStatus.SCHEDULED) {
            throw new ChatException("취소된 일정은 변경할 수 없어.", HttpStatus.CONFLICT);
        }
    }

    private void requireNotStarted(ChatSchedule schedule) {
        if (schedule.getStartsAt() == null || !schedule.getStartsAt().isAfter(Instant.now())) {
            throw new ChatException("이미 시작한 일정은 변경할 수 없어.", HttpStatus.CONFLICT);
        }
    }

    private void requireVersion(ChatSchedule schedule, Long expectedVersion) {
        if (expectedVersion == null || schedule.getVersion() != expectedVersion) {
            throw new ChatException(
                    "다른 사용자가 일정을 먼저 변경했어. 새로고침 후 다시 시도해 줘.",
                    HttpStatus.CONFLICT);
        }
    }

    private void requireMutationAllowed(ChatRoom room, Long requesterId) {
        if (room.getStatus() == ChatRoomStatus.CLOSED) {
            throw new ChatException("닫힌 채팅방의 일정은 변경할 수 없어.", HttpStatus.CONFLICT);
        }
        if (room.getType() != ChatRoomType.ONE_ON_ONE) {
            return;
        }
        room.getParticipants().stream()
                .filter(participant -> !participant.getId().equals(requesterId))
                .findFirst()
                .ifPresent(participant -> {
                    if (userBlockRepository.existsBetween(requesterId, participant.getId())) {
                        throw new ChatException(
                                "차단 관계인 사용자와는 1:1 채팅 일정을 변경할 수 없어.",
                                HttpStatus.FORBIDDEN);
                    }
                });
    }

    private Message requireCard(String roomId, String scheduleId) {
        return messageRepository.findBySchedule_IdAndChatRoom_Id(scheduleId, roomId)
                .orElseThrow(() -> new ChatException("일정 카드를 찾을 수 없어.", HttpStatus.NOT_FOUND));
    }

    private void publishCard(ChatRoom room, Message card) {
        // This payload is shared by every room member. currentUserStatus must stay
        // null here; clients derive their own state from the participant list.
        MessageDto sharedMessage = MessageDto.fromEntity(card, null);
        applicationEventPublisher.publishEvent(new ChatScheduleCardChangedEvent(
                sharedMessage,
                room.getId(),
                room.getParticipants().stream().map(User::getId).toList()));
    }

    private void refreshRoomPreviewWhenCardIsLatest(ChatRoom room, Message card) {
        if (card.getCreatedAt() != null && card.getCreatedAt().equals(room.getLastMessageTime())) {
            room.setLastMessage(card.getContent());
            chatRoomRepository.save(room);
        }
    }

    private void flushWithConflict(ChatSchedule schedule) {
        try {
            scheduleRepository.saveAndFlush(schedule);
        } catch (OptimisticLockingFailureException exception) {
            throw new ChatException(
                    "다른 사용자가 일정을 먼저 변경했어. 새로고침 후 다시 시도해 줘.",
                    HttpStatus.CONFLICT);
        }
    }

    private String requireTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) {
            throw new ChatException("일정 이름을 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        return title;
    }

    private Instant requireFuture(Instant startsAt) {
        if (startsAt == null || !startsAt.isAfter(Instant.now())) {
            throw new ChatException("일정 시작 시간은 현재 이후여야 해.", HttpStatus.BAD_REQUEST);
        }
        return startsAt;
    }

    private void applyLocation(
            ChatSchedule schedule,
            String location,
            String locationAddress,
            Double latitude,
            Double longitude,
            String kakaoPlaceId
    ) {
        if ((latitude == null) != (longitude == null)) {
            throw new ChatException("위도와 경도는 함께 입력해야 해.", HttpStatus.BAD_REQUEST);
        }
        schedule.setLocation(trimToNull(location));
        schedule.setLocationAddress(trimToNull(locationAddress));
        schedule.setLatitude(latitude);
        schedule.setLongitude(longitude);
        schedule.setKakaoPlaceId(trimToNull(kakaoPlaceId));
    }

    private String cardPreview(ChatSchedule schedule) {
        return schedule.getStatus() == ChatScheduleStatus.CANCELLED
                ? "취소된 일정: " + schedule.getTitle()
                : "일정: " + schedule.getTitle();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
