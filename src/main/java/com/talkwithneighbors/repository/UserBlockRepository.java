package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {
    Optional<UserBlock> findByBlocker_IdAndBlocked_Id(Long blockerId, Long blockedId);

    boolean existsByBlocker_IdAndBlocked_Id(Long blockerId, Long blockedId);

    @Query("select case when count(b) > 0 then true else false end from UserBlock b " +
            "where (b.blocker.id = :firstId and b.blocked.id = :secondId) " +
            "or (b.blocker.id = :secondId and b.blocked.id = :firstId)")
    boolean existsBetween(@Param("firstId") Long firstId, @Param("secondId") Long secondId);

    @Query("select case when count(b) > 0 then true else false end from UserBlock b " +
            "where (b.blocker.id = :userId and b.blocked.id = :candidateId) " +
            "or (b.blocker.id = :candidateId and b.blocked.id = :userId)")
    boolean excludesCandidate(@Param("userId") Long userId, @Param("candidateId") Long candidateId);

    List<UserBlock> findByBlocker_IdOrderByCreatedAtDesc(Long blockerId);

    @Query("select case when b.blocker.id = :userId then b.blocked.id else b.blocker.id end " +
            "from UserBlock b where b.blocker.id = :userId or b.blocked.id = :userId")
    List<Long> findExcludedUserIds(@Param("userId") Long userId);
}
