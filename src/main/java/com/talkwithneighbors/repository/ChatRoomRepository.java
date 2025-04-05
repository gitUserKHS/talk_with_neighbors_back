package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

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
} 