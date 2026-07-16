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
import com.talkwithneighbors.entity.MeetupTimeBasis;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
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
        materializeLegacyScheduleIfNeeded(room);
        scheduleRepository.saveAndFlush(schedule);
        recomputeRoomProjection(room, Instant.now());

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

        chatRoomRepository.save(room);

        publishCard(room, card);
        return ChatScheduleDto.fromEntity(schedule, requesterId);
    }

    public List<ChatScheduleDto> list(String roomId, Long requesterId) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipantForUpdate(roomId, requester);
        materializeLegacyScheduleIfNeeded(room);
        recomputeRoomProjection(room, Instant.now());
        reconcileRoomPreview(room);
        chatRoomRepository.save(room);
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

    /**
     * Reconciles public-room projections before reminder selection. This also
     * catches a newly created event that is earlier than an existing future
     * projection, not only projections that have already expired. Legacy
     * profile dates are materialized under the room lock first.
     */
    public void reconcilePublicMeetupProjections(Instant now) {
        Instant effectiveNow = now != null ? now : Instant.now();
        List<String> roomIds = chatRoomRepository
                .findPublicMeetupIdsRequiringScheduleProjectionReconciliation(
                        effectiveNow,
                        ChatScheduleStatus.SCHEDULED,
                        ChatRoomStatus.ACTIVE);
        for (String roomId : roomIds) {
            ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId).orElse(null);
            if (room == null) {
                continue;
            }
            materializeLegacyScheduleIfNeeded(room);
            recomputeRoomProjection(room, effectiveNow);
            chatRoomRepository.save(room);
        }
    }

    void materializeLegacyProfileSchedule(ChatRoom room) {
        materializeLegacyScheduleIfNeeded(room);
    }

    /** Expand-phase adapter for requests sent by the previous frontend. */
    void synchronizeLegacyProfileSchedule(ChatRoom room) {
        materializeLegacyScheduleIfNeeded(room);
        synchronizeLegacyProfileSchedule(room, null, null, false);
    }

    /**
     * @param editedProjectionStart projection shown by the old form before its
     *                              edit
     * @param editedProjectionDuration projected duration shown before the edit
     */
    void synchronizeLegacyProfileSchedule(
            ChatRoom room,
            Instant editedProjectionStart,
            Integer editedProjectionDuration
    ) {
        // HobbyMeetupService already materialized the pre-edit projection while
        // holding the room lock. Re-materializing here would mistake the newly
        // submitted target for an unrepresented legacy event.
        synchronizeLegacyProfileSchedule(
                room, editedProjectionStart, editedProjectionDuration, true);
    }

    private void synchronizeLegacyProfileSchedule(
            ChatRoom room,
            Instant editedProjectionStart,
            Integer editedProjectionDuration,
            boolean existingRoomEdit
    ) {
        Instant requestedStart = MeetupTimePolicy.toInstant(
                room.getScheduledAt(), room.getMeetupTimeBasis());
        Integer requestedDuration = room.getDurationMinutes() != null
                ? room.getDurationMinutes() : 120;
        boolean scheduleFieldsChanged = existingRoomEdit
                && (!Objects.equals(requestedStart, editedProjectionStart)
                || !Objects.equals(requestedDuration, editedProjectionDuration));
        String scheduleId = deterministicId("legacy-chat-schedule:", room.getId());
        ChatSchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            if (scheduleFieldsChanged) {
                throw legacyScheduleEditRequiresCalendar();
            }
            recomputeRoomProjection(room, Instant.now());
            chatRoomRepository.save(room);
            return;
        }
        boolean scheduleBacksEditedProjection = !existingRoomEdit
                || Objects.equals(schedule.getStartsAt(), editedProjectionStart);
        if (schedule.getStatus() != ChatScheduleStatus.SCHEDULED
                || !scheduleBacksEditedProjection
                || (scheduleFieldsChanged && !schedule.getStartsAt().isAfter(Instant.now()))) {
            if (scheduleFieldsChanged) {
                throw legacyScheduleEditRequiresCalendar();
            }
            // A cancelled or historical deterministic event must never be
            // moved/reactivated by a stale full-form profile submission.
            recomputeRoomProjection(room, Instant.now());
            chatRoomRepository.save(room);
            return;
        }

        if (!Objects.equals(schedule.getStartsAt(), requestedStart)
                && scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                        room.getId(), ChatScheduleStatus.SCHEDULED, requestedStart)) {
            throw legacyScheduleEditRequiresCalendar();
        }

        User creator = room.getCreator();
        schedule.setCreator(creator);
        schedule.setTitle(legacyScheduleTitle(room));
        schedule.setDescription(room.getDescription());
        schedule.setStartsAt(requestedStart);
        schedule.setDurationMinutes(requestedDuration);
        schedule.setTimeZone(MeetupTimePolicy.LEGACY_ZONE.getId());
        schedule.setLocation(room.getLocation());
        schedule.setLocationAddress(room.getLocationAddress());
        schedule.setLatitude(room.getLatitude());
        schedule.setLongitude(room.getLongitude());
        schedule.setKakaoPlaceId(room.getKakaoPlaceId());
        if (rsvpRepository.findBySchedule_IdAndUser_Id(scheduleId, creator.getId()).isEmpty()) {
            schedule.addRsvp(new ChatScheduleRsvp(
                    schedule, creator, ChatScheduleRsvpStatus.ATTENDING));
        }
        scheduleRepository.saveAndFlush(schedule);

        Message card = messageRepository
                .findBySchedule_IdAndChatRoom_Id(scheduleId, room.getId())
                .orElseGet(() -> {
                    Message created = new Message();
                    created.setId(deterministicId(
                            "legacy-chat-schedule-message:", scheduleId));
                    created.setChatRoom(room);
                    created.setSender(creator);
                    created.setType(Message.MessageType.SCHEDULE);
                    created.setSchedule(schedule);
                    created.setCreatedAt(room.getLastMessageTime() != null
                            ? room.getLastMessageTime().minusSeconds(1)
                            : LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                    created.getReadByUsers().add(creator.getId());
                    return created;
                });
        card.setContent(cardPreview(schedule));
        messageRepository.saveAndFlush(card);
        recomputeRoomProjection(room, Instant.now());
        chatRoomRepository.save(room);
    }

    private ChatException legacyScheduleEditRequiresCalendar() {
        return new ChatException(
                "모임 달력에서 일정을 수정해 줘.", HttpStatus.CONFLICT);
    }

    public ChatScheduleDto update(
            String roomId,
            String scheduleId,
            Long requesterId,
            UpdateChatScheduleRequest request
    ) {
        User requester = requireUser(requesterId);
        ChatRoom room = requireParticipantForUpdate(roomId, requester);
        requireMutationAllowed(room, requesterId);
        ChatSchedule schedule = requireDetailedSchedule(roomId, scheduleId);
        requireCreator(schedule, requesterId);
        requireScheduled(schedule);
        requireNotStarted(schedule);
        requireVersion(schedule, request.version());
        materializeLegacyScheduleIfNeeded(room);

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
        recomputeRoomProjection(room, Instant.now());
        Message card = requireCard(roomId, scheduleId);
        card.setContent(cardPreview(schedule));
        card.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageRepository.save(card);
        chatRoomRepository.save(room);
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
        ChatRoom room = requireParticipantForUpdate(roomId, requester);
        requireMutationAllowed(room, requesterId);
        ChatSchedule schedule = requireDetailedSchedule(roomId, scheduleId);
        requireCreator(schedule, requesterId);
        if (schedule.getStatus() == ChatScheduleStatus.CANCELLED) {
            return ChatScheduleDto.fromEntity(schedule, requesterId);
        }
        requireNotStarted(schedule);
        requireVersion(schedule, request.version());
        materializeLegacyScheduleIfNeeded(room);

        schedule.setStatus(ChatScheduleStatus.CANCELLED);
        schedule.setCancelledAt(Instant.now());
        flushWithConflict(schedule);
        recomputeRoomProjection(room, Instant.now());
        Message card = requireCard(roomId, scheduleId);
        card.setContent(cardPreview(schedule));
        card.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageRepository.save(card);
        chatRoomRepository.save(room);
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

    /**
     * Keeps the legacy room columns as a read-optimized projection only. The
     * calendar table remains the source of truth. Registration deadlines stay
     * untouched during the expand phase so an older backend can still roll back.
     */
    private void recomputeRoomProjection(ChatRoom room, Instant now) {
        Instant previousStart = MeetupTimePolicy.toInstant(
                room.getScheduledAt(), room.getMeetupTimeBasis());
        var nextSchedule = scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        room.getId(), ChatScheduleStatus.SCHEDULED, now);
        Instant nextStart = nextSchedule.map(ChatSchedule::getStartsAt).orElse(null);

        if (!Objects.equals(previousStart, nextStart)) {
            room.setReminderSentAt(null);
        }
        if (nextSchedule.isPresent()) {
            ChatSchedule schedule = nextSchedule.get();
            room.setScheduledAt(LocalDateTime.ofInstant(schedule.getStartsAt(), ZoneOffset.UTC));
            room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
            room.setDurationMinutes(schedule.getDurationMinutes());
        } else {
            room.setScheduledAt(null);
            room.setMeetupTimeBasis(null);
            room.setDurationMinutes(null);
        }
    }

    /**
     * Converts a pre-calendar meetup date exactly once while holding the room
     * lock. Stable identifiers make retries safe without adding a migration
     * marker column. The synthetic card is deliberately silent and does not
     * replace or move the room's last-message preview.
     */
    private void materializeLegacyScheduleIfNeeded(ChatRoom room) {
        if (room.getScheduledAt() == null) {
            return;
        }

        String scheduleId = deterministicId("legacy-chat-schedule:", room.getId());
        Instant startsAt = MeetupTimePolicy.toInstant(
                room.getScheduledAt(), room.getMeetupTimeBasis());
        if (scheduleRepository.existsById(scheduleId)
                || scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                        room.getId(), ChatScheduleStatus.SCHEDULED, startsAt)) {
            return;
        }

        User creator = room.getCreator();
        if (creator == null) {
            throw new IllegalStateException("A meetup room must have a creator");
        }
        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(scheduleId);
        schedule.setRoom(room);
        schedule.setCreator(creator);
        schedule.setTitle(legacyScheduleTitle(room));
        schedule.setDescription(room.getDescription());
        schedule.setStartsAt(startsAt);
        schedule.setDurationMinutes(
                room.getDurationMinutes() != null ? room.getDurationMinutes() : 120);
        // The legacy profile stored only an instant/basis, not the user's IANA
        // zone. This Korea-local product consistently displayed it in Seoul.
        schedule.setTimeZone(MeetupTimePolicy.LEGACY_ZONE.getId());
        schedule.setLocation(room.getLocation());
        schedule.setLocationAddress(room.getLocationAddress());
        schedule.setLatitude(room.getLatitude());
        schedule.setLongitude(room.getLongitude());
        schedule.setKakaoPlaceId(room.getKakaoPlaceId());
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        schedule.addRsvp(new ChatScheduleRsvp(
                schedule, creator, ChatScheduleRsvpStatus.ATTENDING));
        scheduleRepository.saveAndFlush(schedule);

        Message card = new Message();
        card.setId(deterministicId("legacy-chat-schedule-message:", scheduleId));
        card.setChatRoom(room);
        card.setSender(creator);
        card.setContent(cardPreview(schedule));
        card.setType(Message.MessageType.SCHEDULE);
        card.setSchedule(schedule);
        card.setCreatedAt(room.getLastMessageTime() != null
                ? room.getLastMessageTime().minusSeconds(1)
                : LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        card.getReadByUsers().add(creator.getId());
        messageRepository.saveAndFlush(card);
    }

    /** Lazily repairs previews written by the older inline-card behavior. */
    private void reconcileRoomPreview(ChatRoom room) {
        if (room.getLastMessageTime() == null
                || !messageRepository.existsByChatRoom_IdAndTypeAndCreatedAt(
                        room.getId(), Message.MessageType.SCHEDULE, room.getLastMessageTime())) {
            return;
        }
        List<Message> visibleMessages = messageRepository
                .findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                        room.getId(), Message.MessageType.SCHEDULE, PageRequest.of(0, 1));
        if (visibleMessages == null || visibleMessages.isEmpty()) {
            room.setLastMessage(null);
            room.setLastMessageTime(null);
            return;
        }
        Message latest = visibleMessages.get(0);
        room.setLastMessage(visibleMessagePreview(latest));
        room.setLastMessageTime(latest.getCreatedAt());
    }

    private String visibleMessagePreview(Message message) {
        if (message.getContent() != null && !message.getContent().isBlank()) {
            return message.getContent();
        }
        var attachments = message.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return "새 메시지";
        }
        if (attachments.size() > 1) {
            return "첨부 파일 " + attachments.size() + "개";
        }
        return switch (attachments.get(0).getType()) {
            case IMAGE -> "사진";
            case VIDEO -> "동영상";
            case FILE -> "파일: " + attachments.get(0).getOriginalName();
        };
    }

    private String deterministicId(String namespace, String roomId) {
        return UUID.nameUUIDFromBytes((namespace + roomId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String legacyScheduleTitle(ChatRoom room) {
        String title = trimToNull(room.getName());
        if (title == null) {
            return "Meetup schedule";
        }
        return title.length() <= 80 ? title : title.substring(0, 80);
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
