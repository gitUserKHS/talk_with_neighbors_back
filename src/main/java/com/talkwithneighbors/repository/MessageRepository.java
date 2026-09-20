package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import com.talkwithneighbors.entity.User;

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

    @Query("""
            SELECT m FROM Message m
            WHERE m.chatRoom.id = :roomId AND m.type <> :excludedType
            ORDER BY m.createdAt DESC
            """)
    Page<Message> findVisibleByChatRoomIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId,
            @Param("excludedType") Message.MessageType excludedType,
            Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findActiveByChatRoomIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId, Pageable pageable);

    @Query("""
            SELECT m FROM Message m
            WHERE m.chatRoom.id = :roomId
              AND m.isDeleted = false
              AND m.type <> :excludedType
            ORDER BY m.createdAt DESC
            """)
    List<Message> findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
            @Param("roomId") String roomId,
            @Param("excludedType") Message.MessageType excludedType,
            Pageable pageable);

    @Query("SELECT DISTINCT m FROM Message m LEFT JOIN FETCH m.attachments WHERE m.chatRoom.id = :roomId")
    List<Message> findAllWithAttachmentsByChatRoomId(@Param("roomId") String roomId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    List<Message> findUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);

    @Query("""
            SELECT m FROM Message m
            WHERE m.chatRoom.id = :roomId
              AND m.isDeleted = false
              AND m.type <> :excludedType
              AND :userId NOT IN (SELECT u FROM m.readByUsers u)
            """)
    List<Message> findVisibleUnreadMessages(
            @Param("roomId") String roomId,
            @Param("userId") Long userId,
            @Param("excludedType") Message.MessageType excludedType);
    
    /**
     * 방의 메시지 중 본인이 보내지 않았고 삭제되지 않았으며 아직 읽지 않은 것 전부를
     * 한 문장으로 읽음 처리합니다. 메시지마다 컬렉션을 로드해 행을 넣는 대신
     * INSERT ... SELECT 한 번으로 끝내며, NOT EXISTS 덕분에 중복 호출에도 안전합니다.
     * H2와 MySQL 8.4에서 같은 SQL이 동작합니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 읽은 사용자 ID
     * @return 새로 추가된 읽음 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO message_read_by (message_id, user_id)
            SELECT m.id, :userId
            FROM messages m
            WHERE m.chat_room_id = :roomId
              AND m.is_deleted = false
              AND m.sender_id <> :userId
              AND NOT EXISTS (
                SELECT 1 FROM message_read_by r
                WHERE r.message_id = m.id AND r.user_id = :userId
              )
            """, nativeQuery = true)
    int markAllVisibleAsRead(@Param("roomId") String roomId, @Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :roomId AND :userId NOT IN (SELECT u FROM m.readByUsers u)")
    long countUnreadMessages(@Param("roomId") String roomId, @Param("userId") Long userId);

    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.chatRoom.id = :roomId
              AND m.isDeleted = false
              AND m.type <> :excludedType
              AND :userId NOT IN (SELECT u FROM m.readByUsers u)
            """)
    long countVisibleUnreadMessages(
            @Param("roomId") String roomId,
            @Param("userId") Long userId,
            @Param("excludedType") Message.MessageType excludedType);

    boolean existsByChatRoom_IdAndTypeAndCreatedAt(
            String roomId,
            Message.MessageType type,
            java.time.LocalDateTime createdAt);

    @Query("""
            SELECT DISTINCT message.chatRoom.id
            FROM Message message
            WHERE message.type = :scheduleType
              AND message.createdAt = message.chatRoom.lastMessageTime
              AND :user MEMBER OF message.chatRoom.participants
            ORDER BY message.chatRoom.id
            """)
    List<String> findParticipantRoomIdsWithSchedulePreview(
            @Param("user") User user,
            @Param("scheduleType") Message.MessageType scheduleType);

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
