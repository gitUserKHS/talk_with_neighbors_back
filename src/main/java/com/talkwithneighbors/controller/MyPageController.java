package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.dto.mypage.*;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.MyPageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mypage")
@RequiredArgsConstructor
public class MyPageController {
    private final MyPageService myPageService;

    @GetMapping("/overview")
    public ResponseEntity<MyPageOverviewDto> overview(UserSession session) {
        return ResponseEntity.ok(myPageService.overview(session.getUserId()));
    }

    @GetMapping("/posts")
    public ResponseEntity<List<FeedPostDto>> posts(UserSession session) {
        return ResponseEntity.ok(myPageService.posts(session.getUserId()));
    }

    @GetMapping("/comments")
    public ResponseEntity<List<MyCommentActivityDto>> comments(UserSession session) {
        return ResponseEntity.ok(myPageService.comments(session.getUserId()));
    }

    @GetMapping("/likes")
    public ResponseEntity<List<FeedPostDto>> likes(UserSession session) {
        return ResponseEntity.ok(myPageService.likes(session.getUserId()));
    }

    @GetMapping("/meetups")
    public ResponseEntity<List<HobbyMeetupDto>> meetups(UserSession session) {
        return ResponseEntity.ok(myPageService.meetups(session.getUserId()));
    }

    @GetMapping("/preferences")
    public ResponseEntity<UserPreferencesDto> preferences(UserSession session) {
        return ResponseEntity.ok(myPageService.preferences(session.getUserId()));
    }

    @PutMapping("/preferences")
    public ResponseEntity<UserPreferencesDto> updatePreferences(
            @RequestBody UpdateUserPreferencesRequest request, UserSession session) {
        return ResponseEntity.ok(myPageService.updatePreferences(session.getUserId(), request));
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               UserSession session) {
        myPageService.changePassword(session.getUserId(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(UserSession session) {
        myPageService.logoutAll(session.getUserId());
        return ResponseEntity.noContent().build();
    }
}
