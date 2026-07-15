package com.talkwithneighbors.service;

import com.talkwithneighbors.domain.event.ChatScheduleCardChangedEvent;
import com.talkwithneighbors.dto.schedule.CancelChatScheduleRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatScheduleServiceTest {
    @Mock ChatScheduleRepository scheduleRepository;
    @Mock ChatScheduleRsvpRepository rsvpRepository;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock UserRepository userRepository;
    @Mock UserBlockRepository userBlockRepository;
    @Mock MessageRepository messageRepository;
    @Mock ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks ChatScheduleService service;

    private User host;
    private User member;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        host = user(1L, "host", "/profiles/host.webp");
        member = user(2L, "member", "/profiles/member.webp");
        room = new ChatRoom();
        room.setId("room-1");
        room.setName("동네 채팅");
        room.setType(ChatRoomType.GROUP);
        room.setCreator(host);
        room.setParticipants(new HashSet<>(List.of(host, member)));
    }

    @Test
    void participantCreatesScheduleWithHostAttendingAndStableCard() {
        AtomicReference<ChatSchedule> savedSchedule = new AtomicReference<>();
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.saveAndFlush(any(ChatSchedule.class)))
                .thenAnswer(invocation -> {
                    ChatSchedule saved = initialize(invocation.getArgument(0));
                    savedSchedule.set(saved);
                    return saved;
                });
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedSchedule.get()));
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(room.getId(), host.getId(), createRequest("저녁 산책"));

        assertThat(result.title()).isEqualTo("저녁 산책");
        assertThat(result.currentUserStatus()).isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
        assertThat(result.participants()).singleElement().satisfies(participant -> {
            assertThat(participant.userId()).isEqualTo(host.getId());
            assertThat(participant.nickname()).isEqualTo("host");
            assertThat(participant.profileImage()).isEqualTo("/profiles/host.webp");
            assertThat(participant.host()).isTrue();
            assertThat(participant.status()).isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
        });

        ArgumentCaptor<Message> card = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(card.capture());
        assertThat(card.getValue().getType()).isEqualTo(Message.MessageType.SCHEDULE);
        assertThat(card.getValue().getSchedule().getId()).isEqualTo(result.id());
        assertThat(room.getLastMessage()).isNull();
        assertThat(room.getLastMessageTime()).isNull();
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(savedSchedule.get().getStartsAt(), ZoneOffset.UTC));
        assertThat(room.getDurationMinutes()).isEqualTo(90);
        assertThat(room.getMeetupTimeBasis()).isEqualTo(MeetupTimeBasis.UTC);
        assertThat(room.getRegistrationDeadline()).isNull();

        ArgumentCaptor<ChatScheduleCardChangedEvent> event =
                ArgumentCaptor.forClass(ChatScheduleCardChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().participantIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(event.getValue().message().getSchedule().currentUserStatus()).isNull();
    }

    @Test
    void createMaterializesUnrepresentedLegacyEventOnceBeforeProjectionChanges() {
        Instant legacyStart = Instant.now().plusSeconds(86_400);
        room.setScheduledAt(LocalDateTime.ofInstant(
                legacyStart, MeetupTimePolicy.LEGACY_ZONE));
        room.setMeetupTimeBasis(null);
        room.setDurationMinutes(60);
        room.setLastMessage("기존 대화");
        LocalDateTime previewTime = LocalDateTime.now().minusMinutes(1);
        room.setLastMessageTime(previewTime);
        List<ChatSchedule> persisted = new ArrayList<>();

        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.existsById(any()))
                .thenAnswer(invocation -> persisted.stream().anyMatch(
                        schedule -> schedule.getId().equals(invocation.getArgument(0))));
        when(scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(any(), any(), any()))
                .thenAnswer(invocation -> persisted.stream().anyMatch(
                        schedule -> schedule.getStatus() == invocation.getArgument(1)
                                && schedule.getStartsAt().equals(invocation.getArgument(2))));
        when(scheduleRepository.saveAndFlush(any(ChatSchedule.class)))
                .thenAnswer(invocation -> {
                    ChatSchedule saved = initialize(invocation.getArgument(0));
                    persisted.add(saved);
                    return saved;
                });
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenAnswer(invocation -> persisted.stream()
                        .filter(schedule -> schedule.getStatus() == ChatScheduleStatus.SCHEDULED)
                        .filter(schedule -> schedule.getStartsAt().isAfter(invocation.getArgument(2)))
                        .min(java.util.Comparator.comparing(ChatSchedule::getStartsAt)));

        service.create(room.getId(), host.getId(), createRequest("새 달력 일정 1"));
        service.create(room.getId(), host.getId(), createRequest("새 달력 일정 2"));

        assertThat(persisted).hasSize(3);
        assertThat(persisted).filteredOn(schedule -> schedule.getId().equals(
                        UUID.nameUUIDFromBytes(
                                ("legacy-chat-schedule:" + room.getId())
                                        .getBytes(StandardCharsets.UTF_8)).toString()))
                .singleElement()
                .satisfies(schedule -> assertThat(schedule.getStartsAt()).isEqualTo(legacyStart));
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(legacyStart, ZoneOffset.UTC));
        assertThat(room.getLastMessage()).isEqualTo("기존 대화");
        assertThat(room.getLastMessageTime()).isEqualTo(previewTime);
        verify(messageRepository, times(3)).saveAndFlush(any(Message.class));
    }

    @Test
    void outsiderCannotCreateOrLearnWhetherRoomExists() {
        User outsider = user(9L, "outsider", null);
        when(userRepository.findById(outsider.getId())).thenReturn(Optional.of(outsider));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.create(
                room.getId(), outsider.getId(), createRequest("몰래 만든 일정")))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(scheduleRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void closedRoomRejectsScheduleMutationsButCanStillBeRead() {
        room.setStatus(ChatRoomStatus.CLOSED);
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.create(
                room.getId(), host.getId(), createRequest("닫힌 방 일정")))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(scheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void roomCanContainMultipleSchedules() {
        ChatSchedule first = schedule("schedule-1", host, "아침 산책");
        ChatSchedule second = schedule("schedule-2", member, "저녁 식사");
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByRoomId(room.getId()))
                .thenReturn(List.of(first, second));

        var schedules = service.list(room.getId(), member.getId());

        assertThat(schedules).extracting(result -> result.id())
                .containsExactly("schedule-1", "schedule-2");
    }

    @Test
    void listMaterializesLegacyMeetupExactlyOnceWithoutReorderingChat() {
        LocalDateTime legacyWallClock = LocalDateTime.of(2099, 8, 1, 19, 0);
        Instant expectedStart = MeetupTimePolicy.toInstant(legacyWallClock, null);
        LocalDateTime previewTime = LocalDateTime.of(2026, 7, 16, 12, 0);
        room.setScheduledAt(legacyWallClock);
        room.setMeetupTimeBasis(null);
        room.setDurationMinutes(75);
        room.setRegistrationDeadline(legacyWallClock.minusHours(2));
        room.setLocation("서울숲");
        room.setLocationAddress("서울 성동구 뚝섬로 273");
        room.setLatitude(37.5444);
        room.setLongitude(127.0374);
        room.setKakaoPlaceId("legacy-place");
        room.setLastMessage("기존 마지막 메시지");
        room.setLastMessageTime(previewTime);

        AtomicReference<ChatSchedule> persisted = new AtomicReference<>();
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.existsById(any()))
                .thenAnswer(invocation -> persisted.get() != null);
        when(scheduleRepository.saveAndFlush(any(ChatSchedule.class)))
                .thenAnswer(invocation -> {
                    ChatSchedule saved = initialize(invocation.getArgument(0));
                    persisted.set(saved);
                    return saved;
                });
        when(messageRepository.saveAndFlush(any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(scheduleRepository.findDetailedByRoomId(room.getId()))
                .thenAnswer(invocation -> List.of(persisted.get()));

        var first = service.list(room.getId(), host.getId());
        var second = service.list(room.getId(), host.getId());

        assertThat(first).singleElement().satisfies(schedule -> {
            assertThat(schedule.id()).isEqualTo(second.get(0).id());
            assertThat(schedule.startsAt().toInstant()).isEqualTo(expectedStart);
            assertThat(schedule.durationMinutes()).isEqualTo(75);
            assertThat(schedule.location()).isEqualTo("서울숲");
            assertThat(schedule.currentUserStatus())
                    .isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
        });
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(expectedStart, ZoneOffset.UTC));
        assertThat(room.getMeetupTimeBasis()).isEqualTo(MeetupTimeBasis.UTC);
        assertThat(room.getRegistrationDeadline()).isEqualTo(legacyWallClock.minusHours(2));
        assertThat(room.getLastMessage()).isEqualTo("기존 마지막 메시지");
        assertThat(room.getLastMessageTime()).isEqualTo(previewTime);

        ArgumentCaptor<Message> migratedCard = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(1)).saveAndFlush(migratedCard.capture());
        String migratedScheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        assertThat(migratedCard.getValue().getId()).isEqualTo(
                UUID.nameUUIDFromBytes(
                        ("legacy-chat-schedule-message:" + migratedScheduleId)
                                .getBytes(StandardCharsets.UTF_8))
                        .toString());
        assertThat(migratedCard.getValue().getCreatedAt()).isBefore(previewTime);
        verify(scheduleRepository, times(1)).saveAndFlush(any(ChatSchedule.class));
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void oldProfileScheduleChangeCannotResurrectCancelledCanonicalSchedule() {
        Instant requestedStart = Instant.parse("2099-08-01T10:00:00Z");
        String scheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        ChatSchedule cancelled = schedule(scheduleId, host, "Cancelled event");
        cancelled.setStartsAt(requestedStart.minusSeconds(86_400));
        cancelled.setStatus(ChatScheduleStatus.CANCELLED);
        cancelled.setCancelledAt(Instant.parse("2099-07-31T00:00:00Z"));
        room.setScheduledAt(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(90);
        room.setRegistrationDeadline(room.getScheduledAt().minusHours(1));

        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.synchronizeLegacyProfileSchedule(
                room, cancelled.getStartsAt(), cancelled.getDurationMinutes()))
                .isInstanceOfSatisfying(ChatException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).contains("모임 달력");
                });

        assertThat(cancelled.getStatus()).isEqualTo(ChatScheduleStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC));
        assertThat(room.getRegistrationDeadline())
                .isEqualTo(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC).minusHours(1));
        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void oldProfileEditFromNextProjectionDoesNotMovePastLegacyHistoryToNewTarget() {
        Instant pastStart = Instant.parse("2026-07-15T10:00:00Z");
        Instant nextStart = Instant.parse("2099-08-01T10:00:00Z");
        Instant editedTarget = Instant.parse("2099-09-01T10:00:00Z");
        String legacyScheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        ChatSchedule pastLegacy = schedule(legacyScheduleId, host, "Past legacy event");
        pastLegacy.setStartsAt(pastStart);
        ChatSchedule next = schedule("next-event", member, "Next event");
        next.setStartsAt(nextStart);
        room.setScheduledAt(LocalDateTime.ofInstant(editedTarget, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(next.getDurationMinutes());

        when(scheduleRepository.findById(legacyScheduleId)).thenReturn(Optional.of(pastLegacy));

        assertThatThrownBy(() -> service.synchronizeLegacyProfileSchedule(
                room, nextStart, next.getDurationMinutes()))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(HttpStatus.CONFLICT));

        assertThat(pastLegacy.getStartsAt()).isEqualTo(pastStart);
        assertThat(pastLegacy.getStatus()).isEqualTo(ChatScheduleStatus.SCHEDULED);
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(editedTarget, ZoneOffset.UTC));
        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void unchangedOldProfileResubmissionKeepsHistoricalLegacyAndNextProjection() {
        Instant pastStart = Instant.parse("2026-07-15T10:00:00Z");
        Instant nextStart = Instant.parse("2099-08-01T10:00:00Z");
        String legacyScheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        ChatSchedule pastLegacy = schedule(legacyScheduleId, host, "Past legacy event");
        pastLegacy.setStartsAt(pastStart);
        ChatSchedule next = schedule("next-event", member, "Next event");
        next.setStartsAt(nextStart);
        next.setDurationMinutes(90);
        room.setScheduledAt(LocalDateTime.ofInstant(nextStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(90);

        when(scheduleRepository.findById(legacyScheduleId)).thenReturn(Optional.of(pastLegacy));
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenReturn(Optional.of(next));

        service.synchronizeLegacyProfileSchedule(room, nextStart, 90);

        assertThat(pastLegacy.getStartsAt()).isEqualTo(pastStart);
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(nextStart, ZoneOffset.UTC));
        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(chatRoomRepository).save(room);
    }

    @Test
    void currentFutureLegacyProjectionCanBeMovedByOldProfileForm() {
        Instant previousStart = Instant.parse("2099-08-01T10:00:00Z");
        Instant requestedStart = Instant.parse("2099-09-01T10:00:00Z");
        String scheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        ChatSchedule current = schedule(scheduleId, host, "Current legacy event");
        current.setStartsAt(previousStart);
        current.setDurationMinutes(60);
        Message card = card("legacy-card", current);
        room.setScheduledAt(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(150);

        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(current));
        when(rsvpRepository.findBySchedule_IdAndUser_Id(scheduleId, host.getId()))
                .thenReturn(Optional.of(current.getRsvps().get(0)));
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(scheduleId, room.getId()))
                .thenReturn(Optional.of(card));
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenReturn(Optional.of(current));

        service.synchronizeLegacyProfileSchedule(room, previousStart, 60);

        assertThat(current.getStartsAt()).isEqualTo(requestedStart);
        assertThat(current.getDurationMinutes()).isEqualTo(150);
        verify(scheduleRepository).saveAndFlush(current);
        verify(messageRepository).saveAndFlush(card);
    }

    @Test
    void missingDeterministicIdentityRejectsOldProfileScheduleChange() {
        Instant previousStart = Instant.parse("2099-08-01T10:00:00Z");
        Instant requestedStart = Instant.parse("2099-09-01T10:00:00Z");
        room.setScheduledAt(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(90);

        assertThatThrownBy(() -> service.synchronizeLegacyProfileSchedule(
                room, previousStart, 90))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(HttpStatus.CONFLICT));

        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void startedLegacyEventCannotBeMovedIntoTheFutureByOldProfileForm() {
        Instant pastStart = Instant.parse("2026-07-15T10:00:00Z");
        Instant requestedStart = Instant.parse("2099-09-01T10:00:00Z");
        String scheduleId = UUID.nameUUIDFromBytes(
                ("legacy-chat-schedule:" + room.getId()).getBytes(StandardCharsets.UTF_8))
                .toString();
        ChatSchedule started = schedule(scheduleId, host, "Started legacy event");
        started.setStartsAt(pastStart);
        room.setScheduledAt(LocalDateTime.ofInstant(requestedStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(started.getDurationMinutes());
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(started));

        assertThatThrownBy(() -> service.synchronizeLegacyProfileSchedule(
                room, pastStart, started.getDurationMinutes()))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(HttpStatus.CONFLICT));

        assertThat(started.getStartsAt()).isEqualTo(pastStart);
        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void listRepairsLegacySchedulePreviewFromNewestVisibleMessage() {
        LocalDateTime scheduleCardTime = LocalDateTime.of(2026, 7, 16, 12, 0);
        Message visible = new Message();
        visible.setId("visible-message");
        visible.setChatRoom(room);
        visible.setSender(member);
        visible.setType(Message.MessageType.TEXT);
        visible.setContent("달력 밖의 최신 대화");
        visible.setCreatedAt(scheduleCardTime.minusMinutes(5));
        room.setLastMessage("일정: 예전 약속");
        room.setLastMessageTime(scheduleCardTime);

        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(messageRepository.existsByChatRoom_IdAndTypeAndCreatedAt(
                room.getId(), Message.MessageType.SCHEDULE, scheduleCardTime)).thenReturn(true);
        when(messageRepository.findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of(visible));
        when(scheduleRepository.findDetailedByRoomId(room.getId())).thenReturn(List.of());

        service.list(room.getId(), host.getId());

        assertThat(room.getLastMessage()).isEqualTo("달력 밖의 최신 대화");
        assertThat(room.getLastMessageTime()).isEqualTo(visible.getCreatedAt());
    }

    @Test
    void legacyProfileEventSurvivesAlongsideExistingCalendarSchedule() {
        LocalDateTime legacyWallClock = LocalDateTime.of(2099, 8, 1, 19, 0);
        Instant legacyStart = MeetupTimePolicy.toInstant(legacyWallClock, null);
        room.setScheduledAt(legacyWallClock);
        room.setDurationMinutes(120);
        ChatSchedule actual = schedule("actual-schedule", host, "달력 일정");
        actual.setStartsAt(Instant.parse("2099-09-01T10:00:00Z"));
        actual.setDurationMinutes(45);
        AtomicReference<ChatSchedule> migrated = new AtomicReference<>();

        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.saveAndFlush(any(ChatSchedule.class)))
                .thenAnswer(invocation -> {
                    ChatSchedule saved = initialize(invocation.getArgument(0));
                    migrated.set(saved);
                    return saved;
                });
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(migrated.get()));
        when(scheduleRepository.findDetailedByRoomId(room.getId()))
                .thenAnswer(invocation -> List.of(migrated.get(), actual));

        var schedules = service.list(room.getId(), host.getId());

        assertThat(schedules).extracting(result -> result.id())
                .containsExactly(migrated.get().getId(), "actual-schedule");
        assertThat(migrated.get().getStartsAt()).isEqualTo(legacyStart);
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(legacyStart, ZoneOffset.UTC));
        assertThat(room.getDurationMinutes()).isEqualTo(120);
        verify(scheduleRepository, times(1)).saveAndFlush(any());
        verify(messageRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void periodicAdvanceMovesPassedProjectionToNextCalendarEvent() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        ChatSchedule passed = schedule("passed", host, "지난 일정");
        passed.setStartsAt(now.minusSeconds(60));
        ChatSchedule upcoming = schedule("upcoming", host, "다음 일정");
        upcoming.setStartsAt(now.plusSeconds(12 * 3_600));
        upcoming.setDurationMinutes(90);
        room.setPublicRoom(true);
        room.setStatus(ChatRoomStatus.ACTIVE);
        room.setScheduledAt(LocalDateTime.ofInstant(passed.getStartsAt(), ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(passed.getDurationMinutes());
        room.setRegistrationDeadline(room.getScheduledAt().minusHours(1));
        room.setReminderSentAt(room.getScheduledAt().minusHours(24));

        when(chatRoomRepository.findPublicMeetupIdsRequiringScheduleProjectionReconciliation(
                now,
                ChatScheduleStatus.SCHEDULED,
                ChatRoomStatus.ACTIVE)).thenReturn(List.of(room.getId()));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                room.getId(), ChatScheduleStatus.SCHEDULED, passed.getStartsAt())).thenReturn(true);
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        room.getId(), ChatScheduleStatus.SCHEDULED, now))
                .thenReturn(Optional.of(upcoming));

        service.reconcilePublicMeetupProjections(now);

        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(upcoming.getStartsAt(), ZoneOffset.UTC));
        assertThat(room.getDurationMinutes()).isEqualTo(90);
        assertThat(room.getMeetupTimeBasis()).isEqualTo(MeetupTimeBasis.UTC);
        assertThat(room.getRegistrationDeadline())
                .isEqualTo(LocalDateTime.ofInstant(passed.getStartsAt(), ZoneOffset.UTC).minusHours(1));
        assertThat(room.getReminderSentAt()).isNull();
        verify(chatRoomRepository).save(room);
        verify(scheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void periodicReconciliationSelectsEarlierEventWhenCurrentProjectionIsStillFuture() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        ChatSchedule projected = schedule("projected", host, "Projected event");
        projected.setStartsAt(now.plusSeconds(20 * 3_600));
        ChatSchedule earlier = schedule("earlier", host, "Earlier event");
        earlier.setStartsAt(now.plusSeconds(12 * 3_600));
        earlier.setDurationMinutes(90);
        room.setPublicRoom(true);
        room.setStatus(ChatRoomStatus.ACTIVE);
        room.setScheduledAt(LocalDateTime.ofInstant(projected.getStartsAt(), ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(projected.getDurationMinutes());
        room.setRegistrationDeadline(room.getScheduledAt().minusHours(1));
        room.setReminderSentAt(room.getScheduledAt().minusHours(24));

        when(chatRoomRepository.findPublicMeetupIdsRequiringScheduleProjectionReconciliation(
                now, ChatScheduleStatus.SCHEDULED, ChatRoomStatus.ACTIVE))
                .thenReturn(List.of(room.getId()));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                room.getId(), ChatScheduleStatus.SCHEDULED, projected.getStartsAt()))
                .thenReturn(true);
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        room.getId(), ChatScheduleStatus.SCHEDULED, now))
                .thenReturn(Optional.of(earlier));

        service.reconcilePublicMeetupProjections(now);

        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(earlier.getStartsAt(), ZoneOffset.UTC));
        assertThat(room.getDurationMinutes()).isEqualTo(90);
        assertThat(room.getRegistrationDeadline())
                .isEqualTo(LocalDateTime.ofInstant(projected.getStartsAt(), ZoneOffset.UTC).minusHours(1));
        assertThat(room.getReminderSentAt()).isNull();
        verify(chatRoomRepository).save(room);
        verify(scheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlyScheduleCreatorCanUpdate() {
        ChatSchedule schedule = schedule("schedule-1", host, "기존 일정");
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.update(
                room.getId(), schedule.getId(), member.getId(), updateRequest(0L, "변경")))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(scheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void creatorUpdatesScheduleAndRebroadcastsSameCardId() {
        ChatSchedule schedule = schedule("schedule-1", host, "기존 일정");
        schedule.setVersion(3L);
        Instant originalStart = schedule.getStartsAt();
        Instant movedStart = originalStart.plusSeconds(86_400);
        Message card = card("message-1", schedule);
        room.setLastMessage("기존 일반 메시지");
        room.setLastMessageTime(card.getCreatedAt().minusSeconds(1));
        room.setScheduledAt(LocalDateTime.ofInstant(originalStart, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(schedule.getDurationMinutes());
        room.setRegistrationDeadline(room.getScheduledAt().minusHours(1));
        room.setReminderSentAt(room.getScheduledAt().minusHours(24));
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                room.getId(), ChatScheduleStatus.SCHEDULED, originalStart))
                .thenReturn(true);
        when(scheduleRepository.saveAndFlush(schedule)).thenAnswer(invocation -> {
            schedule.setVersion(4L);
            return schedule;
        });
        when(scheduleRepository
                .findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
                        any(), any(), any()))
                .thenReturn(Optional.of(schedule));
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        var result = service.update(
                room.getId(), schedule.getId(), host.getId(),
                updateRequest(3L, "변경된 일정", movedStart.atOffset(ZoneOffset.UTC)));

        assertThat(result.title()).isEqualTo("변경된 일정");
        assertThat(result.version()).isEqualTo(4L);
        ArgumentCaptor<ChatScheduleCardChangedEvent> event =
                ArgumentCaptor.forClass(ChatScheduleCardChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().message().getId()).isEqualTo("message-1");
        assertThat(event.getValue().message().getSchedule().currentUserStatus()).isNull();
        assertThat(room.getLastMessage()).isEqualTo("기존 일반 메시지");
        assertThat(room.getLastMessageTime()).isEqualTo(card.getCreatedAt().minusSeconds(1));
        assertThat(room.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(movedStart, ZoneOffset.UTC));
        assertThat(room.getMeetupTimeBasis()).isEqualTo(MeetupTimeBasis.UTC);
        assertThat(room.getRegistrationDeadline())
                .isEqualTo(LocalDateTime.ofInstant(originalStart, ZoneOffset.UTC).minusHours(1));
        assertThat(room.getReminderSentAt()).isNull();
    }

    @Test
    void staleVersionIsRejected() {
        ChatSchedule schedule = schedule("schedule-1", host, "기존 일정");
        schedule.setVersion(5L);
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.update(
                room.getId(), schedule.getId(), host.getId(), updateRequest(4L, "충돌")))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(scheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void creatorCancelsInsteadOfDeletingSchedule() {
        ChatSchedule schedule = schedule("schedule-1", host, "취소할 일정");
        Message card = card("message-1", schedule);
        room.setScheduledAt(LocalDateTime.ofInstant(schedule.getStartsAt(), ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(schedule.getDurationMinutes());
        room.setRegistrationDeadline(room.getScheduledAt().minusHours(1));
        room.setReminderSentAt(room.getScheduledAt().minusHours(24));
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.existsByRoom_IdAndStatusAndStartsAt(
                room.getId(), ChatScheduleStatus.SCHEDULED, schedule.getStartsAt())).thenReturn(true);
        when(scheduleRepository.saveAndFlush(schedule)).thenReturn(schedule);
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        var result = service.cancel(
                room.getId(), schedule.getId(), host.getId(), new CancelChatScheduleRequest(0L));

        assertThat(result.status()).isEqualTo(ChatScheduleStatus.CANCELLED);
        assertThat(result.cancelledAt()).isNotNull();
        assertThat(card.getContent()).isEqualTo("취소된 일정: 취소할 일정");
        assertThat(room.getScheduledAt()).isNull();
        assertThat(room.getMeetupTimeBasis()).isNull();
        assertThat(room.getDurationMinutes()).isNull();
        assertThat(room.getRegistrationDeadline())
                .isEqualTo(LocalDateTime.ofInstant(schedule.getStartsAt(), ZoneOffset.UTC).minusHours(1));
        assertThat(room.getReminderSentAt()).isNull();
        verify(scheduleRepository, never()).delete(any());
    }

    @Test
    void participantRsvpIsAnIdempotentUpsertAndRebroadcastsCard() {
        ChatSchedule schedule = schedule("schedule-1", host, "참석 조사");
        Message card = card("message-1", schedule);
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), member))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findLockedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(rsvpRepository.findBySchedule_IdAndUser_Id(schedule.getId(), member.getId()))
                .thenReturn(Optional.empty());
        when(rsvpRepository.saveAndFlush(any(ChatScheduleRsvp.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        var result = service.rsvp(
                room.getId(), schedule.getId(), member.getId(),
                new UpdateChatScheduleRsvpRequest(ChatScheduleRsvpStatus.NOT_ATTENDING));

        assertThat(result.currentUserStatus()).isEqualTo(ChatScheduleRsvpStatus.NOT_ATTENDING);
        assertThat(result.participants()).extracting(participant -> participant.nickname())
                .contains("host", "member");
        ArgumentCaptor<ChatScheduleRsvp> response = ArgumentCaptor.forClass(ChatScheduleRsvp.class);
        verify(rsvpRepository).saveAndFlush(response.capture());
        assertThat(response.getValue().getStatus()).isEqualTo(ChatScheduleRsvpStatus.NOT_ATTENDING);
        verify(scheduleRepository).saveAndFlush(schedule);
        verify(applicationEventPublisher).publishEvent(any(ChatScheduleCardChangedEvent.class));
    }

    @Test
    void unchangedRsvpIsANoOpWithoutVersionOrWebSocketChurn() {
        ChatSchedule schedule = schedule("schedule-1", host, "참석 조사");
        ChatScheduleRsvp memberRsvp = new ChatScheduleRsvp(
                schedule, member, ChatScheduleRsvpStatus.ATTENDING);
        schedule.addRsvp(memberRsvp);
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), member))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findLockedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(rsvpRepository.findBySchedule_IdAndUser_Id(schedule.getId(), member.getId()))
                .thenReturn(Optional.of(memberRsvp));

        var result = service.rsvp(
                room.getId(), schedule.getId(), member.getId(),
                new UpdateChatScheduleRsvpRequest(ChatScheduleRsvpStatus.ATTENDING));

        assertThat(result.currentUserStatus()).isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
        verify(rsvpRepository, never()).saveAndFlush(any());
        verify(scheduleRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).findBySchedule_IdAndChatRoom_Id(any(), any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void hostCannotRsvpNotAttending() {
        ChatSchedule schedule = schedule("schedule-1", host, "참석 조사");
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findLockedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.rsvp(
                room.getId(), schedule.getId(), host.getId(),
                new UpdateChatScheduleRsvpRequest(ChatScheduleRsvpStatus.NOT_ATTENDING)))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(rsvpRepository, never()).saveAndFlush(any());
    }

    @Test
    void startedScheduleCannotBeUpdated() {
        ChatSchedule schedule = schedule("schedule-1", host, "이미 시작");
        schedule.setStartsAt(Instant.now().minusSeconds(1));
        assertThat(com.talkwithneighbors.dto.schedule.ChatScheduleDto
                .fromEntity(schedule, host.getId()).canEdit()).isFalse();
        assertThat(com.talkwithneighbors.dto.schedule.ChatScheduleDto
                .fromEntity(schedule, host.getId()).canCancel()).isFalse();
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.update(
                room.getId(), schedule.getId(), host.getId(), updateRequest(0L, "늦은 변경")))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void fullFormUpdateCanClearAnExistingLocation() {
        ChatSchedule schedule = schedule("schedule-1", host, "장소 제거");
        schedule.setLocation("서울숲");
        schedule.setLocationAddress("서울 성동구");
        schedule.setLatitude(37.5);
        schedule.setLongitude(127.0);
        schedule.setKakaoPlaceId("place-1");
        Message card = card("message-1", schedule);
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId()))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.saveAndFlush(schedule)).thenReturn(schedule);
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        service.update(room.getId(), schedule.getId(), host.getId(), updateRequest(0L, "장소 제거"));

        assertThat(schedule.getLocation()).isNull();
        assertThat(schedule.getLocationAddress()).isNull();
        assertThat(schedule.getLatitude()).isNull();
        assertThat(schedule.getLongitude()).isNull();
        assertThat(schedule.getKakaoPlaceId()).isNull();
    }

    @Test
    void cancelledScheduleRejectsRsvp() {
        ChatSchedule schedule = schedule("schedule-1", host, "취소됨");
        schedule.setStatus(ChatScheduleStatus.CANCELLED);
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), member))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findLockedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.rsvp(
                room.getId(), schedule.getId(), member.getId(),
                new UpdateChatScheduleRsvpRequest(ChatScheduleRsvpStatus.ATTENDING)))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(rsvpRepository, never()).saveAndFlush(any());
    }

    private CreateChatScheduleRequest createRequest(String title) {
        return new CreateChatScheduleRequest(
                title,
                "같이 걸어요",
                OffsetDateTime.now().plusDays(2),
                90,
                "Asia/Seoul",
                "서울숲",
                "서울 성동구 뚝섬로 273",
                37.5444,
                127.0374,
                "kakao-place-1");
    }

    private UpdateChatScheduleRequest updateRequest(Long version, String title) {
        return updateRequest(version, title, null);
    }

    private UpdateChatScheduleRequest updateRequest(
            Long version,
            String title,
            OffsetDateTime startsAt
    ) {
        return new UpdateChatScheduleRequest(
                version,
                title,
                null,
                startsAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ChatSchedule schedule(String id, User creator, String title) {
        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(id);
        schedule.setRoom(room);
        schedule.setCreator(creator);
        schedule.setTitle(title);
        schedule.setStartsAt(Instant.now().plusSeconds(86_400));
        schedule.setDurationMinutes(120);
        schedule.setTimeZone("Asia/Seoul");
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        schedule.setCreatedAt(Instant.now());
        schedule.setUpdatedAt(Instant.now());
        schedule.addRsvp(new ChatScheduleRsvp(
                schedule, creator, ChatScheduleRsvpStatus.ATTENDING));
        return schedule;
    }

    private Message card(String id, ChatSchedule schedule) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(schedule.getCreator());
        message.setType(Message.MessageType.SCHEDULE);
        message.setContent("일정: " + schedule.getTitle());
        message.setSchedule(schedule);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ChatSchedule initialize(ChatSchedule schedule) {
        if (schedule.getCreatedAt() == null) {
            schedule.setCreatedAt(Instant.now());
        }
        if (schedule.getUpdatedAt() == null) {
            schedule.setUpdatedAt(schedule.getCreatedAt());
        }
        return schedule;
    }

    private User user(Long id, String username, String profileImage) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setProfileImage(profileImage);
        return user;
    }
}
