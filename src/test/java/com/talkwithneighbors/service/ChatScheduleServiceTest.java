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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(scheduleRepository.saveAndFlush(any(ChatSchedule.class)))
                .thenAnswer(invocation -> initialize(invocation.getArgument(0)));
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
        assertThat(room.getLastMessage()).isEqualTo("일정: 저녁 산책");

        ArgumentCaptor<ChatScheduleCardChangedEvent> event =
                ArgumentCaptor.forClass(ChatScheduleCardChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().participantIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(event.getValue().message().getSchedule().currentUserStatus()).isNull();
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
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), member))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByRoomId(room.getId()))
                .thenReturn(List.of(first, second));

        var schedules = service.list(room.getId(), member.getId());

        assertThat(schedules).extracting(result -> result.id())
                .containsExactly("schedule-1", "schedule-2");
    }

    @Test
    void onlyScheduleCreatorCanUpdate() {
        ChatSchedule schedule = schedule("schedule-1", host, "기존 일정");
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), member))
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
        Message card = card("message-1", schedule);
        room.setLastMessage(card.getContent());
        room.setLastMessageTime(card.getCreatedAt());
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.saveAndFlush(schedule)).thenAnswer(invocation -> {
            schedule.setVersion(4L);
            return schedule;
        });
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        var result = service.update(
                room.getId(), schedule.getId(), host.getId(), updateRequest(3L, "변경된 일정"));

        assertThat(result.title()).isEqualTo("변경된 일정");
        assertThat(result.version()).isEqualTo(4L);
        ArgumentCaptor<ChatScheduleCardChangedEvent> event =
                ArgumentCaptor.forClass(ChatScheduleCardChangedEvent.class);
        verify(applicationEventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().message().getId()).isEqualTo("message-1");
        assertThat(event.getValue().message().getSchedule().currentUserStatus()).isNull();
        assertThat(room.getLastMessage()).isEqualTo("일정: 변경된 일정");
    }

    @Test
    void staleVersionIsRejected() {
        ChatSchedule schedule = schedule("schedule-1", host, "기존 일정");
        schedule.setVersion(5L);
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
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
        when(userRepository.findById(host.getId())).thenReturn(Optional.of(host));
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
                .thenReturn(Optional.of(room));
        when(scheduleRepository.findDetailedByIdAndRoomId(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.saveAndFlush(schedule)).thenReturn(schedule);
        when(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                .thenReturn(Optional.of(card));

        var result = service.cancel(
                room.getId(), schedule.getId(), host.getId(), new CancelChatScheduleRequest(0L));

        assertThat(result.status()).isEqualTo(ChatScheduleStatus.CANCELLED);
        assertThat(result.cancelledAt()).isNotNull();
        assertThat(card.getContent()).isEqualTo("취소된 일정: 취소할 일정");
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
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
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
        when(chatRoomRepository.findByIdAndParticipantsContaining(room.getId(), host))
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
        return new UpdateChatScheduleRequest(
                version,
                title,
                null,
                null,
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
