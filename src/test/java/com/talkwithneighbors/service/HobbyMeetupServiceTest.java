package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
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
        request.setLocation("서울 중구");
        request.setMaxParticipants(6);

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
        assertEquals(6, savedRoom.getMaxParticipants());
        assertTrue(savedRoom.getParticipants().contains(creator));
        assertEquals(List.of("독서", "산책"), result.getSharedInterests());
    }

    @Test
    void preventsJoiningFullMeetup() {
        User currentUser = user(3L, "new-member", "독서");
        ChatRoom room = publicMeetup("meetup-full", 2);
        room.getParticipants().add(creator);
        room.getParticipants().add(user(2L, "member", "독서"));

        when(userRepository.findById(currentUser.getId())).thenReturn(Optional.of(currentUser));
        when(chatRoomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        ChatException exception = assertThrows(
                ChatException.class,
                () -> hobbyMeetupService.joinMeetup(currentUser.getId(), room.getId())
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertFalse(room.getParticipants().contains(currentUser));
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
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
        verify(chatRoomRepository).save(room);
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
