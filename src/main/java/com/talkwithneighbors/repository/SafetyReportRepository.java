package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.SafetyReport;
import com.talkwithneighbors.entity.SafetyTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SafetyReportRepository extends JpaRepository<SafetyReport, String> {
    boolean existsByReporter_IdAndTargetTypeAndTargetId(Long reporterId, SafetyTargetType targetType, String targetId);
    List<SafetyReport> findByReporter_IdOrderByCreatedAtDesc(Long reporterId);
}
