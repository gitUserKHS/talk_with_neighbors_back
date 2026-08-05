package com.talkwithneighbors.controller;

import com.talkwithneighbors.admin.AdminAccessService;
import com.talkwithneighbors.admin.AdminReportService;
import com.talkwithneighbors.dto.admin.AdminReportDetailDto;
import com.talkwithneighbors.dto.admin.AdminReportDto;
import com.talkwithneighbors.dto.admin.ReviewReportRequest;
import com.talkwithneighbors.entity.ReportStatus;
import com.talkwithneighbors.security.UserSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 신고 검토 큐. 접근 판정은 서비스 계층에서 하며, 운영자가 아니면 404를 돌려준다.
 */
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;
    private final AdminAccessService adminAccessService;

    /**
     * 프런트가 운영 메뉴를 보여줄지 결정할 때 쓴다.
     */
    @GetMapping("/access")
    public ResponseEntity<Map<String, Boolean>> access(UserSession session) {
        return ResponseEntity.ok(Map.of("admin", adminAccessService.isAdmin(session.getUserId())));
    }

    @GetMapping
    public ResponseEntity<Page<AdminReportDto>> queue(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            UserSession session
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ResponseEntity.ok(adminReportService.queue(session.getUserId(), status, pageable));
    }

    @GetMapping("/counts")
    public ResponseEntity<Map<ReportStatus, Long>> counts(UserSession session) {
        return ResponseEntity.ok(adminReportService.statusCounts(session.getUserId()));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<AdminReportDetailDto> detail(@PathVariable String reportId, UserSession session) {
        return ResponseEntity.ok(adminReportService.detail(session.getUserId(), reportId));
    }

    @PatchMapping("/{reportId}")
    public ResponseEntity<AdminReportDetailDto> review(
            @PathVariable String reportId,
            @Valid @RequestBody ReviewReportRequest request,
            UserSession session
    ) {
        return ResponseEntity.ok(adminReportService.review(session.getUserId(), reportId, request));
    }
}
