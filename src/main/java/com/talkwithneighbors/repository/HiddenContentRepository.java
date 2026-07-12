package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.HiddenContent;
import com.talkwithneighbors.entity.SafetyTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HiddenContentRepository extends JpaRepository<HiddenContent, Long> {
    Optional<HiddenContent> findByUser_IdAndTargetTypeAndTargetId(Long userId, SafetyTargetType targetType, String targetId);

    @Query("select h.targetId from HiddenContent h where h.user.id = :userId and h.targetType = :targetType")
    List<String> findTargetIds(@Param("userId") Long userId, @Param("targetType") SafetyTargetType targetType);
}
