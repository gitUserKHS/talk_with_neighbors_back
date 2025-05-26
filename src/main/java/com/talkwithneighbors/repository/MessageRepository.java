package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    /**
     * 특정 채팅방의 모든 메시지 읽음 상태를 삭제합니다.
     * 메시지 삭제 전에 먼저 실행되어야 합니다.
     * 
     * @param chatRoomId 채팅방 ID
     */
    @Modifying
    @Query(value = "DELETE FROM message_read_by WHERE message_id IN (SELECT id FROM messages WHERE chat_room_id = :chatRoomId)", nativeQuery = true)
    void deleteReadStatusByChatRoomId(@Param("chatRoomId") String chatRoomId);

    /**
     * 특정 채팅방의 모든 메시지를 삭제합니다.
     * 읽음 상태가 먼저 삭제된 후 실행되어야 합니다.
     * 
     * @param chatRoomId 채팅방 ID
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.chatRoom.id = :chatRoomId")
    void deleteByChatRoomId(@Param("chatRoomId") String chatRoomId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    List<Message> findUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);
    
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    long countUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND m.sender.id = :userId ORDER BY m.createdAt DESC")
    List<Message> findByChatRoomIdAndSenderIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId, @Param("userId") Long userId, Pageable pageable);
}