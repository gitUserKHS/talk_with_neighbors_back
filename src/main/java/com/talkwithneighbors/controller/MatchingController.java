package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.matching.MatchProfileDto;
import com.talkwithneighbors.dto.matching.MatchingPreferencesDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.MatchingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Void> startMatching(
            @RequestBody MatchingPreferencesDto preferences,
            UserSession userSession
    ) {
        matchingService.startMatching(preferences, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stopMatching(UserSession userSession) {
        matchingService.stopMatching(userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{matchId}/accept")
    public ResponseEntity<Void> acceptMatch(
            @PathVariable("matchId") String matchId,
            UserSession userSession
    ) {
        matchingService.acceptMatch(matchId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{matchId}/reject")
    public ResponseEntity<Void> rejectMatch(
            @PathVariable("matchId") String matchId,
            UserSession userSession
    ) {
        matchingService.rejectMatch(matchId, userSession.getUserId());
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