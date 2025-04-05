package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.MatchingService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
@Slf4j
public class MatchingController {

    private final MatchingService matchingService;

    @RequireLogin
    @PostMapping("/preferences")
    public ResponseEntity<Void> saveMatchingPreferences(
            @RequestBody MatchingPreferencesDto preferences,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.saveMatchingPreferences(preferences, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @PostMapping("/start")
    public ResponseEntity<Void> startMatching(
            @RequestBody MatchingPreferencesDto preferences,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.startMatching(preferences, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @PostMapping("/stop")
    public ResponseEntity<Void> stopMatching(HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.stopMatching(userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @PostMapping("/{matchId}/accept")
    public ResponseEntity<Void> acceptMatch(
            @PathVariable String matchId,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.acceptMatch(matchId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @PostMapping("/{matchId}/reject")
    public ResponseEntity<Void> rejectMatch(
            @PathVariable String matchId,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.rejectMatch(matchId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @GetMapping("/nearby")
    public ResponseEntity<List<MatchProfileDto>> searchNearbyUsers(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam Double radius,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        List<MatchProfileDto> nearbyUsers = matchingService.searchNearbyUsers(
                latitude,
                longitude,
                radius,
                userSession.getUserId()
        );
        return ResponseEntity.ok(nearbyUsers);
    }
    
    @RequireLogin
    @PostMapping("/process-pending")
    public ResponseEntity<Void> processPendingMatches(HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("userSession");
        matchingService.processPendingMatches(userSession.getUserId());
        return ResponseEntity.ok().build();
    }
} 