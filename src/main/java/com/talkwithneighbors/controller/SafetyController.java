package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.safety.*;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.SafetyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/safety")
@RequireLogin
@RequiredArgsConstructor
public class SafetyController {
    private final SafetyService safetyService;

    @PostMapping("/blocks/{userId}")
    public ResponseEntity<BlockedUserDto> block(@PathVariable Long userId, UserSession session) {
        return ResponseEntity.ok(safetyService.blockUser(session.getUserId(), userId));
    }

    @DeleteMapping("/blocks/{userId}")
    public ResponseEntity<Void> unblock(@PathVariable Long userId, UserSession session) {
        safetyService.unblockUser(session.getUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/blocks")
    public ResponseEntity<List<BlockedUserDto>> blocks(UserSession session) {
        return ResponseEntity.ok(safetyService.blockedUsers(session.getUserId()));
    }

    @PostMapping("/reports")
    public ResponseEntity<SafetyReportDto> report(@Valid @RequestBody CreateReportRequest request,
                                                  UserSession session) {
        return ResponseEntity.ok(safetyService.report(session.getUserId(), request));
    }

    @GetMapping("/reports/mine")
    public ResponseEntity<List<SafetyReportDto>> myReports(UserSession session) {
        return ResponseEntity.ok(safetyService.myReports(session.getUserId()));
    }

    @PostMapping("/hidden")
    public ResponseEntity<Void> hide(@Valid @RequestBody ContentVisibilityRequest request, UserSession session) {
        safetyService.hide(session.getUserId(), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/hidden/{targetType}/{targetId}")
    public ResponseEntity<Void> unhide(@PathVariable SafetyTargetType targetType, @PathVariable String targetId,
                                       UserSession session) {
        safetyService.unhide(session.getUserId(), targetType, targetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hidden")
    public ResponseEntity<List<HiddenContentDto>> hidden(UserSession session) {
        return ResponseEntity.ok(safetyService.hiddenContents(session.getUserId()));
    }
}
