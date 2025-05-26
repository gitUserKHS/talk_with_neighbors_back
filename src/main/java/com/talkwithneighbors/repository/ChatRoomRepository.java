package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방을 관리하는 리포지토리 인터페이스
 * 채팅방 관련 데이터베이스 작업을 처리합니다.
 */
@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {
    /**
     * 특정 사용자가 참여한 모든 채팅방 목록을 조회합니다. (페이징 처리)
     * 
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return 채팅방 페이지
     */
    // List<ChatRoom> findByParticipantsId(Long userId); // 이 메서드는 Page<ChatRoom> findByParticipants_Id(Long userId, Pageable pageable); 와 같이 변경 가능

    @Query("SELECT DISTINCT cr FROM ChatRoom cr JOIN FETCH cr.participants WHERE :user MEMBER OF cr.participants")
    Page<ChatRoom> findByParticipantsContaining(@Param("user") User user, Pageable pageable);
    
    /**
     * 특정 사용자가 참여한 모든 채팅방 목록을 최근 활동 순으로 조회합니다.
     * 카카오톡처럼 가장 최근에 메시지가 있었던 채팅방부터 표시됩니다.
     * 
     * @param user 사용자
     * @param pageable 페이징 정보
     * @return 최근 활동 순으로 정렬된 채팅방 페이지
     */
    @Query("SELECT DISTINCT cr FROM ChatRoom cr JOIN FETCH cr.participants WHERE :user MEMBER OF cr.participants ORDER BY cr.lastMessageTime DESC")
    Page<ChatRoom> findByParticipantsContainingOrderByLastMessageTimeDesc(@Param("user") User user, Pageable pageable);
    
    @Query("SELECT cr FROM ChatRoom cr JOIN cr.participants p1 JOIN cr.participants p2 " +
           "WHERE p1 = :user1 AND p2 = :user2 AND SIZE(cr.participants) = 2")
    List<ChatRoom> findByParticipantsContainingAndParticipantsContaining(
            @Param("user1") User user1, @Param("user2") User user2);

    /**
     * 채팅방 타입으로 채팅방을 조회합니다. (페이징 처리)
     * @param type 채팅방 타입
     * @param pageable 페이징 정보
     * @return 채팅방 페이지
     */
    Page<ChatRoom> findByType(ChatRoomType type, Pageable pageable);
    
    /**
     * 채팅방 타입과 이름 또는 ID로 채팅방을 조회합니다. (페이징 처리)
     * @param type1 채팅방 타입
     * @param name 채팅방 이름 (부분 일치)
     * @param type2 채팅방 타입
     * @param id 채팅방 ID (부분 일치)
     * @param pageable 페이징 정보
     * @return 채팅방 페이지
     */
    @Query("SELECT cr FROM ChatRoom cr WHERE (cr.type = :type1 AND LOWER(cr.name) LIKE LOWER(CONCAT('%', :name, '%'))) OR (cr.type = :type2 AND LOWER(cr.id) LIKE LOWER(CONCAT('%', :id, '%')))")
    Page<ChatRoom> findByTypeAndNameContainingIgnoreCaseOrTypeAndIdContainingIgnoreCase(
        @Param("type1") ChatRoomType type1, @Param("name") String name, 
        @Param("type2") ChatRoomType type2, @Param("id") String id, Pageable pageable);

    /**
     * 채팅방 이름 또는 ID로 모든 유형의 채팅방을 조회합니다. (페이징 처리)
     * @param name 채팅방 이름 (부분 일치)
     * @param id 채팅방 ID (부분 일치)
     * @param pageable 페이징 정보
     * @return 채팅방 페이지
     */
    @Query("SELECT cr FROM ChatRoom cr WHERE LOWER(cr.name) LIKE LOWER(CONCAT('%', :name, '%')) OR LOWER(cr.id) LIKE LOWER(CONCAT('%', :id, '%'))")
    Page<ChatRoom> findByNameContainingIgnoreCaseOrIdContainingIgnoreCase(
        @Param("name") String name, @Param("id") String id, Pageable pageable);

    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p WHERE cr.id = :id AND :user MEMBER OF cr.participants")
    Optional<ChatRoom> findByIdAndParticipantsContaining(@Param("id") String id, @Param("user") User user);

    @Query("SELECT size(cr.participants) FROM ChatRoom cr WHERE cr.id = :roomId")
    Integer getParticipantCount(@Param("roomId") String roomId);
    
    // === 매칭 관련 메서드 추가 ===
    /**
     * 특정 사용자가 참여하고 특정 타입인 채팅방 목록을 조회합니다.
     * @param user 사용자
     * @param type 채팅방 타입
     * @return 채팅방 목록
     */
    @Query("SELECT DISTINCT cr FROM ChatRoom cr JOIN FETCH cr.participants WHERE :user MEMBER OF cr.participants AND cr.type = :type")
    List<ChatRoom> findByParticipantsContainingAndType(@Param("user") User user, @Param("type") ChatRoomType type);
} 