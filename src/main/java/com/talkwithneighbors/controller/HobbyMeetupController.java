package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.HobbyMeetupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetups")
@RequiredArgsConstructor
public class HobbyMeetupController {
    private final HobbyMeetupService hobbyMeetupService;

    @GetMapping
    public ResponseEntity<Page<HobbyMeetupDto>> getMeetups(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String interest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            UserSession userSession
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        return ResponseEntity.ok(hobbyMeetupService.findMeetups(
                userSession.getUserId(), keyword, interest, pageable));
    }

    @PostMapping
    public ResponseEntity<HobbyMeetupDto> createMeetup(
            @Valid @RequestBody CreateHobbyMeetupRequest request,
            UserSession userSession
    ) {
        return ResponseEntity.ok(hobbyMeetupService.createMeetup(userSession.getUserId(), request));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<HobbyMeetupDto> getMeetup(
            @PathVariable String roomId,
            UserSession userSession
    ) {
        return ResponseEntity.ok(hobbyMeetupService.getMeetup(userSession.getUserId(), roomId));
    }

    @PatchMapping("/{roomId}")
    public ResponseEntity<HobbyMeetupDto> updateMeetup(
            @PathVariable String roomId,
            @Valid @RequestBody CreateHobbyMeetupRequest request,
            UserSession userSession
    ) {
        return ResponseEntity.ok(hobbyMeetupService.updateMeetup(
                userSession.getUserId(), roomId, request));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteMeetup(
            @PathVariable String roomId,
            UserSession userSession
    ) {
        hobbyMeetupService.deleteMeetup(userSession.getUserId(), roomId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<HobbyMeetupDto> joinMeetup(
            @PathVariable String roomId,
            UserSession userSession
    ) {
        return ResponseEntity.ok(hobbyMeetupService.joinMeetup(userSession.getUserId(), roomId));
    }

    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Void> leaveMeetup(
            @PathVariable String roomId,
            UserSession userSession
    ) {
        hobbyMeetupService.leaveMeetup(userSession.getUserId(), roomId);
        return ResponseEntity.noContent().build();
    }
}
