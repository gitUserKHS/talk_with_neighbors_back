package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 채팅 메시지를 관리하는 리포지토리 인터페이스
 * 메시지 관련 데이터베이스 작업을 처리합니다.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    /**
     * 특정 채팅방의 메시지들을 생성 시간 내림차순으로 조회합니다.
     * 
     * @param chatRoomId 채팅방 ID
     * @return 메시지 목록
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdOrderByCreatedAtDesc(@Param("roomId") String roomId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findActiveByChatRoomIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId, Pageable pageable);

    @Query("SELECT DISTINCT m FROM Message m LEFT JOIN FETCH m.attachments WHERE m.chatRoom.id = :roomId")
    List<Message> findAllWithAttachmentsByChatRoomId(@Param("roomId") String roomId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    List<Message> findUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);
    
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    long countUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND m.sender.id = :userId ORDER BY m.createdAt DESC")
    List<Message> findByChatRoomIdAndSenderIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId, @Param("userId") Long userId, Pageable pageable);

    @Query("""
            SELECT COUNT(DISTINCT m)
            FROM Message m
            JOIN m.attachments attachment
            JOIN m.chatRoom room
            JOIN room.participants participant
            WHERE participant.id = :userId
              AND (attachment.url = :mediaUrl OR attachment.thumbnailUrl = :mediaUrl)
            """)
    long countAccessibleChatAttachments(
            @Param("mediaUrl") String mediaUrl,
            @Param("userId") Long userId
    );

    @EntityGraph(attributePaths = {"schedule", "schedule.creator", "schedule.rsvps", "schedule.rsvps.user"})
    Optional<Message> findBySchedule_IdAndChatRoom_Id(String scheduleId, String roomId);
}
