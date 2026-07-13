package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.dto.mypage.*;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.AuthException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {
    private final UserRepository userRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostLikeRepository postLikeRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final FeedService feedService;
    private final HobbyMeetupService hobbyMeetupService;
    private final PasswordEncoder passwordEncoder;
    private final RedisSessionService redisSessionService;

    @Transactional(readOnly = true)
    public MyPageOverviewDto overview(Long userId) {
        User user = user(userId);
        List<HobbyMeetupDto> meetups = hobbyMeetupService.myMeetups(userId);
        long created = chatRoomRepository.findByCreator_IdAndTypeOrderByScheduledAtDesc(userId, ChatRoomType.GROUP).size();
        return new MyPageOverviewDto(profileCompletion(user),
                feedPostRepository.findByAuthor_IdOrderByCreatedAtDesc(userId).size(),
                postCommentRepository.findByAuthor_IdOrderByCreatedAtDesc(userId).size(),
                postLikeRepository.findByUser_IdOrderByCreatedAtDesc(userId).size(),
                created, meetups.size());
    }

    @Transactional(readOnly = true)
    public List<FeedPostDto> posts(Long userId) { return feedService.myPosts(userId); }

    @Transactional(readOnly = true)
    public List<MyCommentActivityDto> comments(Long userId) { return feedService.myComments(userId); }

    @Transactional(readOnly = true)
    public List<FeedPostDto> likes(Long userId) { return feedService.likedPosts(userId); }

    @Transactional(readOnly = true)
    public List<HobbyMeetupDto> meetups(Long userId) { return hobbyMeetupService.myMeetups(userId); }

    @Transactional(readOnly = true)
    public UserPreferencesDto preferences(Long userId) { return toPreferences(user(userId)); }

    @Transactional
    public UserPreferencesDto updatePreferences(Long userId, UpdateUserPreferencesRequest request) {
        User user = user(userId);
        if (request.profileDiscoverable() != null) user.setProfileDiscoverable(request.profileDiscoverable());
        if (request.showNeighborhood() != null) user.setShowNeighborhood(request.showNeighborhood());
        if (request.matchNotificationsEnabled() != null) user.setMatchNotificationsEnabled(request.matchNotificationsEnabled());
        if (request.chatNotificationsEnabled() != null) user.setChatNotificationsEnabled(request.chatNotificationsEnabled());
        if (request.meetupNotificationsEnabled() != null) user.setMeetupNotificationsEnabled(request.meetupNotificationsEnabled());
        return toPreferences(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = user(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new AuthException("현재 비밀번호가 올바르지 않아요.", HttpStatus.BAD_REQUEST);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new AuthException("새 비밀번호는 현재 비밀번호와 달라야 해요.", HttpStatus.BAD_REQUEST);
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void logoutAll(Long userId) {
        redisSessionService.removeUserSessions(userId.toString());
    }

    private UserPreferencesDto toPreferences(User user) {
        return new UserPreferencesDto(enabled(user.getProfileDiscoverable()), enabled(user.getShowNeighborhood()),
                enabled(user.getMatchNotificationsEnabled()), enabled(user.getChatNotificationsEnabled()),
                enabled(user.getMeetupNotificationsEnabled()));
    }

    private boolean enabled(Boolean value) { return value == null || value; }

    private int profileCompletion(User user) {
        int completed = 0;
        if (user.getUsername() != null && !user.getUsername().isBlank()) completed++;
        if (user.getProfileImage() != null && !user.getProfileImage().isBlank()) completed++;
        if (user.getBio() != null && !user.getBio().isBlank()) completed++;
        if (user.getAge() != null && user.getAge() > 0) completed++;
        if (user.getGender() != null && !user.getGender().isBlank()) completed++;
        if (user.getInterests() != null && !user.getInterests().isEmpty()) completed++;
        if (user.getAddress() != null && !user.getAddress().isBlank()) completed++;
        return Math.round(completed * 100f / 7f);
    }

    private User user(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("사용자를 찾을 수 없어요.", HttpStatus.NOT_FOUND));
    }
}
