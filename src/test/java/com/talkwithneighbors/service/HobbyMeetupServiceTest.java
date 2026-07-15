package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.domain.event.MeetupJoinedEvent;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.entity.MeetupWaitlistEntry;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.MeetupWaitlistRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.ChatScheduleRsvpRepository;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HobbyMeetupServiceTest {
    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private UserBlockRepository userBlockRepository;

    @Mock
    private MeetupWaitlistRepository meetupWaitlistRepository;

    @Mock
    private ChatScheduleRepository chatScheduleRepository;

    @Mock
    private ChatScheduleRsvpRepository chatScheduleRsvpRepository;

    @Mock
    private ChatScheduleService chatScheduleService;

    @Mock
    private OfflineNotificationService offlineNotificationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ChatService chatService;

    @InjectMocks
    private HobbyMeetupService hobbyMeetupService;

    private User creator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        creator = user(1L, "creator", "독서", "산책");
    }

    @Test
    void createsPublicMeetupWithCleanInterestTags() {
        CreateHobbyMeetupRequest request = new CreateHobbyMeetupRequest();
        request.setTitle("주말 독서 산책");
        request.setDescription("읽고 걸어요");
        request.setInterestTags(List.of("독서", " 독서 ", "산책"));
        request.setLocation("서울도서관");
        request.setLocationAddress("서울 중구 세종대로 110");
        request.setLatitude(37.5662968);
        request.setLongitude(126.9779451);
        request.setKakaoPlaceId("123456789");
        request.setMaxParticipants(6);
        request.setScheduledAt(OffsetDateTime.parse("2099-08-01T19:00:00+09:00"));
        request.setRegistrationDeadline(OffsetDateTime.parse("2099-08-01T18:00:00+09:00"));

        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            room.setId("meetup-1");
            return room;
        });

        HobbyMeetupDto result = hobbyMeetupService.createMeetup(creator.getId(), request);

        ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
        verify(chatRoomRepository).save(roomCaptor.capture());
        ChatRoom savedRoom = roomCaptor.getValue();
        assertTrue(savedRoom.isPublicRoom());
        assertEquals(ChatRoomType.GROUP, savedRoom.getType());
        assertEquals(List.of("독서", "산책"), savedRoom.getInterestTags());
        assertEquals("서울도서관", savedRoom.getLocation());
        assertEquals("서울 중구 세종대로 110", savedRoom.getLocationAddress());
        assertEquals(37.5662968, savedRoom.getLatitude());
        assertEquals(126.9779451, savedRoom.getLongitude());
        assertEquals("123456789", savedRoom.getKakaoPlaceId());
        assertEquals(6, savedRoom.getMaxParticipants());
        assertEquals(LocalDateTime.of(2099, 8, 1, 10, 0), savedRoom.getScheduledAt());
        assertEquals(LocalDateTime.of(2099, 8, 1, 9, 0), savedRoom.getRegistrationDeadline());
        assertEquals(MeetupTimeBasis.UTC, savedRoom.getMeetupTimeBasis());
        assertEquals(120, savedRoom.getDurationMinutes());
        assertTrue(savedRoom.getParticipants().contains(creator));
        assertEquals(List.of("독서", "산책"), result.getSharedInterests());
        assertEquals("서울도서관", result.getLocation());
        assertEquals("서울 중구 세종대로 110", result.getLocationAddress());
        assertEquals(37.5662968, result.getLatitude());
        assertEquals(126.9779451, result.getLongitude());
        assertEquals("123456789", result.getKakaoPlaceId());
        assertEquals(OffsetDateTime.parse("2099-08-01T10:00:00Z"), result.getScheduledAt());
        assertEquals(OffsetDateTime.parse("2099-08-01T09:00:00Z"), result.getRegistrationDeadline());
        verify(chatScheduleService).synchronizeLegacyProfileSchedule(savedRoom);
    }

    @Test
    void addsUserToWaitlistWhenMeetupIsFull() {
        User currentUser = user(3L, "new-member", "독서");
        ChatRoom room = publicMeetup("meetup-full", 2);
        room.getParticipants().add(creator);
        room.getParticipants().add(user(2L, "member", "독서"));

        when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        HobbyMeetupDto result = hobbyMeetupService.joinMeetup(currentUser.getId(), room.getId());

        assertFalse(room.getParticipants().contains(currentUser));
        verify(meetupWaitlistRepository).save(any(com.talkwithneighbors.entity.MeetupWaitlistEntry.class));
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }

    @Test
    void publishesEventWhenUserJoinsMeetup() {
        User joiningUser = user(2L, "joining-user", "산책", "서울");
        ChatRoom room = publicMeetup("meetup-1", 4);
        when(userRepository.findById(joiningUser.getId())).thenReturn(Optional.of(joiningUser));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);

        hobbyMeetupService.joinMeetup(joiningUser.getId(), room.getId());

        verify(domainEventPublisher).publish(any(MeetupJoinedEvent.class));
    }

    @Test
    void canonicalCalendarIgnoresPreservedLegacyRegistrationDeadline() {
        User joiningUser = user(2L, "joining-user", "coffee");
        ChatRoom room = publicMeetup("canonical-deadline", 4);
        room.getParticipants().add(creator);
        room.setRegistrationDeadline(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        when(userRepository.findById(joiningUser.getId())).thenReturn(Optional.of(joiningUser));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(chatScheduleRepository.existsByRoom_Id(room.getId())).thenReturn(true);

        hobbyMeetupService.joinMeetup(joiningUser.getId(), room.getId());

        assertTrue(room.getParticipants().contains(joiningUser));
    }

    @Test
    void legacyOnlyMeetupStillEnforcesRegistrationDeadline() {
        User joiningUser = user(2L, "joining-user", "coffee");
        ChatRoom room = publicMeetup("legacy-deadline", 4);
        room.getParticipants().add(creator);
        room.setRegistrationDeadline(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        when(userRepository.findById(joiningUser.getId())).thenReturn(Optional.of(joiningUser));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> hobbyMeetupService.joinMeetup(joiningUser.getId(), room.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertFalse(room.getParticipants().contains(joiningUser));
    }

    @Test
    void leavesMeetupWithoutDeletingTheRoom() {
        User member = user(2L, "member", "카페");
        ChatRoom room = publicMeetup("meetup-leave", 5);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);

        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        hobbyMeetupService.leaveMeetup(member.getId(), room.getId());

        assertFalse(room.getParticipants().contains(member));
        assertTrue(room.getParticipants().contains(creator));
        verify(chatScheduleRsvpRepository)
                .deleteBySchedule_Room_IdAndUser_Id(room.getId(), member.getId());
        verify(chatRoomRepository).save(room);
    }

    @Test
    void creatorMustCancelFutureChatScheduleBeforeLeavingMeetup() {
        User member = user(2L, "member", "카페");
        ChatRoom room = publicMeetup("meetup-with-schedule", 5);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);

        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(chatScheduleRepository.existsByRoom_IdAndCreator_IdAndStatusAndStartsAtAfter(
                org.mockito.ArgumentMatchers.eq(room.getId()),
                org.mockito.ArgumentMatchers.eq(member.getId()),
                org.mockito.ArgumentMatchers.eq(ChatScheduleStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        ChatException exception = assertThrows(
                ChatException.class,
                () -> hobbyMeetupService.leaveMeetup(member.getId(), room.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(room.getParticipants().contains(member));
        verify(chatScheduleRsvpRepository, never())
                .deleteBySchedule_Room_IdAndUser_Id(any(), any());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void detailIncludesHostManagementFlagAndParticipantSummaries() {
        User member = user(2L, "member", "coffee");
        ChatRoom room = publicMeetup("meetup-detail", 5);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        HobbyMeetupDto result = hobbyMeetupService.getMeetup(creator.getId(), room.getId());

        assertTrue(result.isCanManage());
        assertEquals(creator.getId(), result.getCreatorId());
        assertEquals(2, result.getParticipants().size());
        assertTrue(result.getParticipants().get(0).host());
        assertEquals("creator", result.getParticipants().get(0).nickname());
        assertEquals("member", result.getParticipants().get(1).nickname());
    }

    @Test
    void hostUpdatesFullMeetupForm() {
        ChatRoom room = publicMeetup("meetup-update", 5);
        room.getParticipants().add(creator);
        LocalDateTime projectedStart = LocalDateTime.of(2099, 9, 2, 3, 0);
        LocalDateTime projectedDeadline = LocalDateTime.of(2099, 9, 1, 3, 0);
        LocalDateTime reminderSentAt = LocalDateTime.of(2099, 9, 1, 4, 0);
        room.setScheduledAt(projectedStart);
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(90);
        room.setRegistrationDeadline(projectedDeadline);
        room.setReminderSentAt(reminderSentAt);
        CreateHobbyMeetupRequest request = validRequest();
        request.setTitle("Updated meetup");
        request.setInterestTags(List.of("coffee", "walk"));
        request.setMaxParticipants(8);
        request.setScheduledAt(null);
        request.setRegistrationDeadline(null);
        request.setDurationMinutes(null);
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);

        HobbyMeetupDto result = hobbyMeetupService.updateMeetup(
                creator.getId(), room.getId(), request);

        assertEquals("Updated meetup", room.getName());
        assertEquals(List.of("coffee", "walk"), room.getInterestTags());
        assertEquals(8, room.getMaxParticipants());
        assertEquals(projectedStart, room.getScheduledAt());
        assertEquals(MeetupTimeBasis.UTC, room.getMeetupTimeBasis());
        assertEquals(90, room.getDurationMinutes());
        assertEquals(projectedDeadline, room.getRegistrationDeadline());
        assertEquals(reminderSentAt, room.getReminderSentAt());
        assertTrue(result.isCanManage());
        verify(chatRoomRepository).save(room);
    }

    @Test
    void oldFrontendScheduleEditSynchronizesCanonicalEventAfterMaterializingPreviousProjection() {
        ChatRoom room = publicMeetup("legacy-form-update", 5);
        room.getParticipants().add(creator);
        room.setScheduledAt(LocalDateTime.of(2099, 7, 1, 10, 0));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(60);
        room.setRegistrationDeadline(LocalDateTime.of(2099, 7, 1, 9, 0));
        CreateHobbyMeetupRequest request = validRequest();
        request.setScheduledAt(OffsetDateTime.parse("2099-09-01T19:30:00+09:00"));
        request.setRegistrationDeadline(OffsetDateTime.parse("2099-09-01T18:00:00+09:00"));
        request.setDurationMinutes(150);
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);

        hobbyMeetupService.updateMeetup(creator.getId(), room.getId(), request);

        assertEquals(LocalDateTime.of(2099, 9, 1, 10, 30), room.getScheduledAt());
        assertEquals(LocalDateTime.of(2099, 9, 1, 9, 0), room.getRegistrationDeadline());
        assertEquals(MeetupTimeBasis.UTC, room.getMeetupTimeBasis());
        assertEquals(150, room.getDurationMinutes());
        InOrder order = org.mockito.Mockito.inOrder(chatScheduleService);
        order.verify(chatScheduleService).materializeLegacyProfileSchedule(room);
        order.verify(chatScheduleService).synchronizeLegacyProfileSchedule(
                room, Instant.parse("2099-07-01T10:00:00Z"), 60);
    }

    @Test
    void leavingMeetupPromotesOldestWaitlistedUserWhileHoldingRoomLock() {
        User member = user(2L, "member", "coffee");
        User waiting = user(3L, "waiting", "coffee");
        ChatRoom room = publicMeetup("meetup-leave-promote", 2);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);
        MeetupWaitlistEntry waitingEntry = new MeetupWaitlistEntry(room, waiting);

        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(meetupWaitlistRepository.findByRoom_IdOrderByCreatedAtAsc(room.getId()))
                .thenReturn(List.of(waitingEntry));

        hobbyMeetupService.leaveMeetup(member.getId(), room.getId());

        assertFalse(room.getParticipants().contains(member));
        assertTrue(room.getParticipants().contains(waiting));
        assertEquals(2, room.getParticipants().size());
        verify(chatRoomRepository).findByIdForUpdate(room.getId());
        verify(meetupWaitlistRepository).delete(waitingEntry);
        verify(chatRoomRepository).save(room);
    }

    @Test
    void increasingCapacityPromotesOldestWaitlistedUser() {
        User member = user(2L, "member", "coffee");
        User first = user(3L, "first-waiting", "coffee");
        User second = user(4L, "second-waiting", "coffee");
        ChatRoom room = publicMeetup("meetup-capacity", 2);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);
        MeetupWaitlistEntry firstEntry = new MeetupWaitlistEntry(room, first);
        MeetupWaitlistEntry secondEntry = new MeetupWaitlistEntry(room, second);
        CreateHobbyMeetupRequest request = validRequest();
        request.setMaxParticipants(3);
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));
        when(meetupWaitlistRepository.findByRoom_IdOrderByCreatedAtAsc(room.getId()))
                .thenReturn(List.of(firstEntry, secondEntry));
        when(chatRoomRepository.save(room)).thenReturn(room);

        HobbyMeetupDto result = hobbyMeetupService.updateMeetup(
                creator.getId(), room.getId(), request);

        assertTrue(room.getParticipants().contains(first));
        assertFalse(room.getParticipants().contains(second));
        assertEquals(3, result.getParticipantCount());
        verify(meetupWaitlistRepository).delete(firstEntry);
        verify(meetupWaitlistRepository, never()).delete(secondEntry);
    }

    @Test
    void nonHostCannotUpdateMeetup() {
        User member = user(2L, "member", "coffee");
        ChatRoom room = publicMeetup("meetup-update", 5);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);
        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> hobbyMeetupService.updateMeetup(member.getId(), room.getId(), validRequest()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(chatRoomRepository, never()).save(room);
    }

    @Test
    void hostDeletesMeetupThroughAuthorizedGraphDeletion() {
        ChatRoom room = publicMeetup("meetup-delete", 5);
        room.getParticipants().add(creator);
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        hobbyMeetupService.deleteMeetup(creator.getId(), room.getId());

        verify(chatService).deleteRoom(room.getId(), creator.getId());
    }

    @Test
    void hostCannotLeaveAndOrphanMeetup() {
        ChatRoom room = publicMeetup("meetup-host-leave", 5);
        room.getParticipants().add(creator);
        when(userRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
        when(chatRoomRepository.findByIdForUpdate(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> hobbyMeetupService.leaveMeetup(creator.getId(), room.getId()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(room.getParticipants().contains(creator));
        verify(chatRoomRepository, never()).save(room);
    }

    private CreateHobbyMeetupRequest validRequest() {
        CreateHobbyMeetupRequest request = new CreateHobbyMeetupRequest();
        request.setTitle("Meetup");
        request.setDescription("Description");
        request.setInterestTags(List.of("coffee"));
        request.setLocation("Cafe");
        request.setLocationAddress("Seoul");
        request.setLatitude(37.5);
        request.setLongitude(127.0);
        request.setMaxParticipants(5);
        request.setScheduledAt(OffsetDateTime.parse("2099-08-01T19:00:00+09:00"));
        request.setRegistrationDeadline(OffsetDateTime.parse("2099-08-01T18:00:00+09:00"));
        request.setDurationMinutes(120);
        return request;
    }

    private ChatRoom publicMeetup(String id, int maxParticipants) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("취미 모임");
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setMaxParticipants(maxParticipants);
        room.setCreator(creator);
        room.setInterestTags(new ArrayList<>(List.of("독서")));
        return room;
    }

    private User user(Long id, String username, String... interests) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setInterests(new ArrayList<>(List.of(interests)));
        return user;
    }
}
