package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.MatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
@Slf4j
@RequireLogin
public class MatchingController {

    private final MatchingService matchingService;

    @PostMapping("/preferences")
    public ResponseEntity<Void> saveMatchingPreferences(
            @RequestBody MatchingPreferencesDto preferences,
            UserSession userSession
    ) {
        matchingService.saveMatchingPreferences(preferences, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/start")
    public ResponseEntity<List<MatchProfileDto>> startMatching(
            @RequestBody MatchingPreferencesDto preferences,
            UserSession userSession
    ) {
        log.info("[MatchingController] 매칭 시작 요청: userSession={}", userSession);
        if (userSession == null) {
            log.error("[MatchingController] userSession is null! 세션 미인증 상태");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long userId = userSession.getUserId();
        log.info("[MatchingController] 추출된 userId: {}", userId);
        if (userId == null) {
            log.error("[MatchingController] userId가 null! userSession={}", userSession);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.emptyList());
        }
        List<MatchProfileDto> matchProfiles = matchingService.startMatching(preferences, userId);
        return ResponseEntity.ok(matchProfiles);
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<MatchProfileDto>> getRecommendations(UserSession userSession) {
        return ResponseEntity.ok(matchingService.getRecommendations(userSession.getUserId()));
    }

    @GetMapping("/requests/incoming")
    public ResponseEntity<List<MatchProfileDto>> getIncomingRequests(UserSession userSession) {
        return ResponseEntity.ok(matchingService.getIncomingRequests(userSession.getUserId()));
    }

    @PostMapping("/users/{targetUserId}/request")
    public ResponseEntity<MatchProfileDto> requestMatch(
            @PathVariable("targetUserId") Long targetUserId,
            UserSession userSession
    ) {
        return ResponseEntity.ok(matchingService.requestMatch(userSession.getUserId(), targetUserId));
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stopMatching(UserSession userSession) {
        log.info("[MatchingController] 매칭 중지 요청: userSession={}", userSession);
        if (userSession == null) {
            log.error("[MatchingController] userSession is null! 세션 미인증 상태");
            return ResponseEntity.badRequest().build();
        }
        Long userId = userSession.getUserId();
        log.info("[MatchingController] 추출된 userId: {}", userId);
        if (userId == null) {
            log.error("[MatchingController] userId가 null! userSession={}", userSession);
            return ResponseEntity.badRequest().build();
        }
        matchingService.stopMatching(userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{matchId}/accept")
    public ResponseEntity<ChatRoomDto> acceptMatch(
            @PathVariable("matchId") String matchId,
            UserSession userSession
    ) {
        log.info("[MatchingController] acceptMatch request received: matchId={}, userId={}", matchId, userSession.getUserId());
        ChatRoomDto chatRoom = matchingService.acceptMatch(matchId, userSession.getUserId());
        log.info("[MatchingController] acceptMatch completed successfully: matchId={}, userId={}", matchId, userSession.getUserId());
        return ResponseEntity.ok(chatRoom);
    }

    @PostMapping("/{matchId}/reject")
    public ResponseEntity<Void> rejectMatch(
            @PathVariable("matchId") String matchId,
            UserSession userSession
    ) {
        log.info("[MatchingController] rejectMatch request received: matchId={}, userId={}", matchId, userSession.getUserId());
        matchingService.rejectMatch(matchId, userSession.getUserId());
        log.info("[MatchingController] rejectMatch completed successfully: matchId={}, userId={}", matchId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<MatchProfileDto>> searchNearbyUsers(
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam("radius") Double radius,
            UserSession userSession
    ) {
        List<MatchProfileDto> nearbyUsers = matchingService.searchNearbyUsers(
                latitude,
                longitude,
                radius,
                userSession.getUserId()
        );
        return ResponseEntity.ok(nearbyUsers);
    }
    
    @PostMapping("/process-pending")
    public ResponseEntity<Void> processPendingMatches(UserSession userSession) {
        matchingService.processPendingMatches(userSession.getUserId());
        return ResponseEntity.ok().build();
    }
} 
