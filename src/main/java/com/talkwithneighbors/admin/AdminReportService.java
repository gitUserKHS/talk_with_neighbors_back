package com.talkwithneighbors.admin;

import com.talkwithneighbors.dto.admin.AdminReportDetailDto;
import com.talkwithneighbors.dto.admin.AdminReportDto;
import com.talkwithneighbors.dto.admin.ReportedContentDto;
import com.talkwithneighbors.dto.admin.ReviewReportRequest;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.ReportStatus;
import com.talkwithneighbors.entity.SafetyReport;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.SafetyReportRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final AdminAccessService adminAccessService;
    private final SafetyReportRepository safetyReportRepository;
    private final UserRepository userRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final MessageRepository messageRepository;

    @Transactional(readOnly = true)
    public Page<AdminReportDto> queue(Long adminUserId, ReportStatus status, Pageable pageable) {
        adminAccessService.requireAdmin(adminUserId);
        return safetyReportRepository.findQueue(status, pageable)
                .map(report -> AdminReportDto.from(report, sameTargetCount(report)));
    }

    @Transactional(readOnly = true)
    public Map<ReportStatus, Long> statusCounts(Long adminUserId) {
        adminAccessService.requireAdmin(adminUserId);
        Map<ReportStatus, Long> counts = new EnumMap<>(ReportStatus.class);
        for (ReportStatus status : ReportStatus.values()) {
            counts.put(status, safetyReportRepository.countByStatus(status));
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public AdminReportDetailDto detail(Long adminUserId, String reportId) {
        adminAccessService.requireAdmin(adminUserId);
        SafetyReport report = report(reportId);
        return new AdminReportDetailDto(
                AdminReportDto.from(report, sameTargetCount(report)),
                loadContent(report)
        );
    }

    @Transactional
    public AdminReportDetailDto review(Long adminUserId, String reportId, ReviewReportRequest request) {
        adminAccessService.requireAdmin(adminUserId);
        SafetyReport report = report(reportId);
        User reviewer = userRepository.findById(adminUserId)
                .orElseThrow(() -> new MatchingException("운영자 계정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));

        report.review(reviewer, request.status(), request.note(), LocalDateTime.now());
        safetyReportRepository.save(report);

        return new AdminReportDetailDto(
                AdminReportDto.from(report, sameTargetCount(report)),
                loadContent(report)
        );
    }

    private SafetyReport report(String reportId) {
        return safetyReportRepository.findDetail(reportId)
                .orElseThrow(() -> new MatchingException("신고를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private long sameTargetCount(SafetyReport report) {
        return safetyReportRepository.countByTargetTypeAndTargetId(report.getTargetType(), report.getTargetId());
    }

    /**
     * 신고 대상의 현재 내용을 읽는다. 이미 지워졌으면 available=false로 돌려준다.
     */
    private ReportedContentDto loadContent(SafetyReport report) {
        return switch (report.getTargetType()) {
            case FEED_POST -> feedPostRepository.findById(report.getTargetId())
                    .map(this::feedPostContent)
                    .orElseGet(ReportedContentDto::missing);
            case COMMENT -> postCommentRepository.findById(report.getTargetId())
                    .map(this::commentContent)
                    .orElseGet(ReportedContentDto::missing);
            case MESSAGE -> messageRepository.findById(report.getTargetId())
                    .map(this::messageContent)
                    .orElseGet(ReportedContentDto::missing);
            case USER -> parseUserId(report.getTargetId())
                    .flatMap(userRepository::findById)
                    .map(this::userContent)
                    .orElseGet(ReportedContentDto::missing);
        };
    }

    private ReportedContentDto feedPostContent(FeedPost post) {
        User author = post.getAuthor();
        return new ReportedContentDto(true,
                author != null ? author.getId() : null,
                author != null ? author.getUsername() : null,
                post.getCaption(), post.getImageUrl(), post.getCreatedAt());
    }

    private ReportedContentDto commentContent(PostComment comment) {
        User author = comment.getAuthor();
        return new ReportedContentDto(true,
                author != null ? author.getId() : null,
                author != null ? author.getUsername() : null,
                comment.getContent(), null, comment.getCreatedAt());
    }

    private ReportedContentDto messageContent(Message message) {
        User sender = message.getSender();
        return new ReportedContentDto(true,
                sender != null ? sender.getId() : null,
                sender != null ? sender.getUsername() : null,
                message.getContent(), null, message.getCreatedAt());
    }

    private ReportedContentDto userContent(User user) {
        return new ReportedContentDto(true, user.getId(), user.getUsername(),
                user.getBio(), user.getProfileImage(), null);
    }

    private java.util.Optional<Long> parseUserId(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }
}
