package com.talkwithneighbors.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyReportReviewTest {

    private final User reporter = User.builder().id(1L).email("r@example.test").username("reporter")
            .password("x").latitude(0.0).longitude(0.0).address("a").build();
    private final User admin = User.builder().id(2L).email("a@example.test").username("admin")
            .password("x").latitude(0.0).longitude(0.0).address("a").build();

    private SafetyReport report() {
        SafetyReport report = new SafetyReport(reporter, SafetyTargetType.FEED_POST, "post-1",
                ReportReason.SPAM, "광고글");
        report.onCreate();
        return report;
    }

    @Test
    void newReportStartsPendingWithoutResolution() {
        SafetyReport report = report();

        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.getResolvedAt()).isNull();
        assertThat(report.getReviewedBy()).isNull();
    }

    @Test
    void resolvingRecordsTheReviewerAndClosesTheReport() {
        SafetyReport report = report();
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 10, 0);

        report.review(admin, ReportStatus.RESOLVED, "  게시글 삭제함  ", now);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getReviewedBy()).isEqualTo(admin);
        assertThat(report.getReviewNote()).isEqualTo("게시글 삭제함");
        assertThat(report.getReviewedAt()).isEqualTo(now);
        assertThat(report.getResolvedAt()).isEqualTo(now);
    }

    @Test
    void dismissingAlsoCountsAsClosed() {
        SafetyReport report = report();
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 10, 0);

        report.review(admin, ReportStatus.DISMISSED, null, now);

        assertThat(report.getResolvedAt()).isEqualTo(now);
        assertThat(report.getReviewNote()).isNull();
    }

    @Test
    void reopeningForReviewClearsTheResolutionTimestamp() {
        SafetyReport report = report();
        LocalDateTime closed = LocalDateTime.of(2026, 8, 6, 10, 0);
        report.review(admin, ReportStatus.RESOLVED, "처리", closed);

        LocalDateTime reopened = LocalDateTime.of(2026, 8, 6, 11, 0);
        report.review(admin, ReportStatus.REVIEWING, "다시 확인", reopened);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(report.getResolvedAt()).isNull();
        assertThat(report.getReviewedAt()).isEqualTo(reopened);
    }

    @Test
    void blankNoteIsStoredAsNullRatherThanEmptyText() {
        SafetyReport report = report();

        report.review(admin, ReportStatus.REVIEWING, "   ", LocalDateTime.now());

        assertThat(report.getReviewNote()).isNull();
    }
}
