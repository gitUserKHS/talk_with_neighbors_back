package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ReportStatus;
import com.talkwithneighbors.entity.SafetyReport;
import com.talkwithneighbors.entity.SafetyTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SafetyReportRepository extends JpaRepository<SafetyReport, String> {
    boolean existsByReporter_IdAndTargetTypeAndTargetId(Long reporterId, SafetyTargetType targetType, String targetId);

    List<SafetyReport> findByReporter_IdOrderByCreatedAtDesc(Long reporterId);

    /**
     * 운영자 검토 큐. 상태를 지정하지 않으면 전체를 오래된 신고부터 보여준다.
     * 먼저 접수된 신고가 먼저 처리되도록 오름차순으로 정렬한다.
     */
    @Query("select r from SafetyReport r join fetch r.reporter "
            + "where (:status is null or r.status = :status) "
            + "order by r.createdAt asc")
    Page<SafetyReport> findQueue(@Param("status") ReportStatus status, Pageable pageable);

    @Query("select r from SafetyReport r join fetch r.reporter left join fetch r.reviewedBy where r.id = :id")
    Optional<SafetyReport> findDetail(@Param("id") String id);

    long countByStatus(ReportStatus status);

    /**
     * 같은 대상에 접수된 신고 건수. 반복 신고된 콘텐츠를 운영자가 알아볼 수 있게 한다.
     */
    long countByTargetTypeAndTargetId(SafetyTargetType targetType, String targetId);
}
