package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.domain.event.MeetupJoinedEvent;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.MeetupTimeBasis;
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
    private OfflineNotificationService offlineNotificationService;

    @Mock
    private ObjectMapper objectMapper;

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
        assertTrue(savedRoom.getParticipants().contains(creator));
        assertEquals(List.of("독서", "산책"), result.getSharedInterests());
        assertEquals("서울도서관", result.getLocation());
        assertEquals("서울 중구 세종대로 110", result.getLocationAddress());
        assertEquals(37.5662968, result.getLatitude());
        assertEquals(126.9779451, result.getLongitude());
        assertEquals("123456789", result.getKakaoPlaceId());
        assertEquals(OffsetDateTime.of(2099, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC), result.getScheduledAt());
        assertEquals(OffsetDateTime.of(2099, 8, 1, 9, 0, 0, 0, ZoneOffset.UTC), result.getRegistrationDeadline());
    }

    @Test
    void addsUserToWaitlistWhenMeetupIsFull() {
        User currentUser = user(3L, "new-member", "독서");
        ChatRoom room = publicMeetup("meetup-full", 2);
        room.getParticipants().add(creator);
        room.getParticipants().add(user(2L, "member", "독서"));

        when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));

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
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);

        hobbyMeetupService.joinMeetup(joiningUser.getId(), room.getId());

        verify(domainEventPublisher).publish(any(MeetupJoinedEvent.class));
    }

    @Test
    void leavesMeetupWithoutDeletingTheRoom() {
        User member = user(2L, "member", "카페");
        ChatRoom room = publicMeetup("meetup-leave", 5);
        room.getParticipants().add(creator);
        room.getParticipants().add(member);

        when(userRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));

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
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));
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
