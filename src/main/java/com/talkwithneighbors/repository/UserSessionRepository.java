package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
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
     * 만료된 세션 목록을 조회합니다.
     * 
     * @param now 현재 시간
     * @return 만료된 세션 목록
     */
    @Query("SELECT s FROM Session s WHERE s.expiresAt < :now")
    List<Session> findExpiredSessions(@Param("now") LocalDateTime now);

    Optional<Session> findBySessionId(String sessionId);
    
    void deleteBySessionId(String sessionId);

    List<Session> findAllByUserIdAndExpiresAtAfterAndSessionIdNot(Long userId, LocalDateTime expiresAt, String sessionId);
} 