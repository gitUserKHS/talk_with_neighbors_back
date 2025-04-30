package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 매칭 정보를 관리하는 리포지토리 인터페이스
 * 매칭 관련 데이터베이스 작업을 처리합니다.
 */
@Repository
@EnableJpaRepositories
public interface MatchRepository extends JpaRepository<Match, String> {
    /**
     * 특정 매칭 ID와 사용자 ID로 매칭 정보를 조회합니다.
     * 
     * @param matchId 매칭 ID
     * @param userId 사용자 ID
     * @return 매칭 정보 (Optional)
     */
    @Query("SELECT m FROM Match m WHERE m.id = :matchId AND (m.user1.id = :userId OR m.user2.id = :userId)")
    Optional<Match> findByIdAndUserId(@Param("matchId") String matchId, @Param("userId") Long userId);

    /**
     * 특정 사용자의 특정 상태의 매칭 목록을 조회합니다.
     * 
     * @param userId 사용자 ID
     * @param status 매칭 상태
     * @return 매칭 목록
     */
    @Query("SELECT m FROM Match m WHERE (m.user1.id = :userId OR m.user2.id = :userId) AND m.status = :status")
    List<Match> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") MatchStatus status);

    /**
     * 두 사용자 간의 특정 상태의 매칭이 존재하는지 확인합니다.
     * 
     * @param user1Id 첫 번째 사용자 ID
     * @param user2Id 두 번째 사용자 ID
     * @param status 매칭 상태
     * @return 매칭 존재 여부
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM Match m " +
           "WHERE ((m.user1.id = :user1Id AND m.user2.id = :user2Id) OR " +
           "(m.user1.id = :user2Id AND m.user2.id = :user1Id)) AND " +
           "m.status = :status")
    boolean existsByUsersAndStatus(
            @Param("user1Id") Long user1Id,
            @Param("user2Id") Long user2Id,
            @Param("status") MatchStatus status
    );

    /**
     * 특정 상태이고 만료 시간이 지난 매칭 목록을 조회합니다.
     * 
     * @param status 매칭 상태
     * @param now 현재 시간
     * @return 만료된 매칭 목록
     */
    @Query("SELECT m FROM Match m WHERE m.status = :status AND m.expiresAt < :now")
    List<Match> findByStatusAndExpiresAtBefore(
            @Param("status") MatchStatus status,
            @Param("now") LocalDateTime now
    );

    /**
     * 특정 사용자가 참여한 모든 매칭 목록을 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 매칭 목록
     */
    @Query("SELECT m FROM Match m WHERE m.user1.id = :userId OR m.user2.id = :userId")
    List<Match> findByUserId(@Param("userId") Long userId);

    List<Match> findByUser1IdOrUser2IdAndStatus(Long user1Id, Long user2Id, MatchStatus status);

    /**
     * 대기 중인 매칭을 일괄 만료 처리합니다.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Match m SET m.status = :expiredStatus, m.respondedAt = :now " +
           "WHERE (m.user1.id = :userId OR m.user2.id = :userId) AND m.status = :pendingStatus")
    int bulkExpireMatches(@Param("expiredStatus") MatchStatus expiredStatus,
                          @Param("now") LocalDateTime now,
                          @Param("userId") Long userId,
                          @Param("pendingStatus") MatchStatus pendingStatus);
}