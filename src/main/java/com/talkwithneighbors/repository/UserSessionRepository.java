package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 사용자 세션을 관리하는 리포지토리 인터페이스
 * 세션 관련 데이터베이스 작업을 처리합니다.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<Session, String> {
    /**
     * 특정 사용자의 모든 세션을 조회합니다.
     *
     * @param userId 사용자 ID
     * @return 세션 목록
     */
    List<Session> findByUserId(Long userId);

    /**
     * 세션과 소유 사용자를 한 번의 조회로 가져옵니다.
     * 인증 경로에서 지연 로딩으로 인한 두 번째 SELECT를 피하기 위해 사용합니다.
     *
     * @param id 세션 ID
     * @return 사용자가 함께 로딩된 세션
     */
    @Query("select s from Session s join fetch s.user where s.sessionId = :id")
    Optional<Session> findByIdWithUser(@Param("id") String id);

    /**
     * 만료된 세션 ID 목록을 조회합니다. 엔티티 대신 ID만 읽어 정리 작업의 메모리 사용을 줄입니다.
     *
     * @param now 현재 시간
     * @return 만료된 세션 ID 목록
     */
    @Query("select s.sessionId from Session s where s.expiresAt < :now")
    List<String> findExpiredSessionIds(@Param("now") LocalDateTime now);

    /**
     * 만료된 세션을 한 번의 DELETE로 제거합니다.
     *
     * @param now 현재 시간
     * @return 삭제된 행 수
     */
    @Modifying
    @Transactional
    @Query("delete from Session s where s.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") LocalDateTime now);

    Optional<Session> findBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    List<Session> findAllByUserIdAndExpiresAtAfterAndSessionIdNot(Long userId, LocalDateTime expiresAt, String sessionId);
}
