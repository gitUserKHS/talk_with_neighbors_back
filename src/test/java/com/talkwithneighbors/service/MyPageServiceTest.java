package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.mypage.ChangePasswordRequest;
import com.talkwithneighbors.dto.mypage.UpdateUserPreferencesRequest;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MyPageServiceTest {
    @Mock UserRepository userRepository;
    @Mock FeedPostRepository feedPostRepository;
    @Mock PostCommentRepository postCommentRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock FeedService feedService;
    @Mock HobbyMeetupService hobbyMeetupService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisSessionService redisSessionService;
    @InjectMocks MyPageService myPageService;

    private User user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        user = User.builder().id(1L).username("coa").email("coa@example.test").password("encoded")
                .profileImage("https://example.test/profile.png").bio("hello").age(27).gender("female")
                .interests(new ArrayList<>(List.of("산책"))).latitude(37.5).longitude(127.0).address("서울 중구")
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void overviewAggregatesActivityAndProfileCompletion() {
        when(feedPostRepository.findByAuthor_IdOrderByCreatedAtDesc(1L)).thenReturn(List.of(new FeedPost()));
        when(postCommentRepository.findByAuthor_IdOrderByCreatedAtDesc(1L)).thenReturn(List.of(new PostComment()));
        when(postLikeRepository.findByUser_IdOrderByCreatedAtDesc(1L)).thenReturn(List.of(new PostLike()));
        when(chatRoomRepository.findByCreator_IdAndTypeOrderByScheduledAtDesc(1L, ChatRoomType.GROUP))
                .thenReturn(List.of(new ChatRoom()));
        when(hobbyMeetupService.myMeetups(1L)).thenReturn(List.of(mock(com.talkwithneighbors.dto.meetup.HobbyMeetupDto.class)));

        var result = myPageService.overview(1L);

        assertEquals(100, result.profileCompletion());
        assertEquals(1, result.postCount());
        assertEquals(1, result.commentCount());
        assertEquals(1, result.likedPostCount());
        assertEquals(1, result.createdMeetupCount());
        assertEquals(1, result.joinedMeetupCount());
    }

    @Test
    void preferencesDefaultToEnabledAndCanBeUpdated() {
        user.setProfileDiscoverable(null);
        assertTrue(myPageService.preferences(1L).profileDiscoverable());

        var result = myPageService.updatePreferences(1L,
                new UpdateUserPreferencesRequest(false, false, false, true, false));

        assertFalse(result.profileDiscoverable());
        assertFalse(result.showNeighborhood());
        assertFalse(result.matchNotificationsEnabled());
        assertTrue(result.chatNotificationsEnabled());
        assertFalse(result.meetupNotificationsEnabled());
    }

    @Test
    void passwordChangeRequiresCurrentPassword() {
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        assertThrows(AuthException.class,
                () -> myPageService.changePassword(1L, new ChangePasswordRequest("wrong", "new-password")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void passwordChangeEncodesNewPassword() {
        when(passwordEncoder.matches("current", "encoded")).thenReturn(true);
        when(passwordEncoder.matches("new-password", "encoded")).thenReturn(false);
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

        myPageService.changePassword(1L, new ChangePasswordRequest("current", "new-password"));

        assertEquals("new-encoded", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void logoutAllRemovesEveryUserSession() {
        myPageService.logoutAll(1L);
        verify(redisSessionService).removeUserSessions("1");
    }
}
