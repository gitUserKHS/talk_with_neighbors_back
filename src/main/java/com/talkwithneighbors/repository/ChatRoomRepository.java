package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
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
     * 특정 사용자가 참여한 모든 채팅방 목록을 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 채팅방 목록
     */
    List<ChatRoom> findByParticipantsId(Long userId);

    @Query("SELECT cr FROM ChatRoom cr JOIN cr.participants p WHERE p = :user")
    List<ChatRoom> findByParticipantsContaining(@Param("user") User user);
    
    @Query("SELECT cr FROM ChatRoom cr JOIN cr.participants p1 JOIN cr.participants p2 " +
           "WHERE p1 = :user1 AND p2 = :user2 AND SIZE(cr.participants) = 2")
    List<ChatRoom> findByParticipantsContainingAndParticipantsContaining(
            @Param("user1") User user1, @Param("user2") User user2);

    /**
     * 채팅방 타입으로 채팅방을 조회합니다.
     * @param type 채팅방 타입
     * @return 채팅방 목록
     */
    List<ChatRoom> findByType(ChatRoomType type);
    
    /**
     * 채팅방 타입과 이름 또는 ID로 채팅방을 조회합니다.
     * @param type1 채팅방 타입
     * @param name 채팅방 이름 (부분 일치)
     * @param type2 채팅방 타입
     * @param id 채팅방 ID (부분 일치)
     * @return 채팅방 목록
     */
    List<ChatRoom> findByTypeAndNameContainingIgnoreCaseOrTypeAndIdContainingIgnoreCase(
        ChatRoomType type1, String name, ChatRoomType type2, String id);

    Optional<ChatRoom> findByIdAndParticipantsContaining(String id, User user);
} 