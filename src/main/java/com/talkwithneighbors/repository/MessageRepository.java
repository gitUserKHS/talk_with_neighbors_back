package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
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
    List<Message> findByChatRoomIdOrderByCreatedAtDesc(String chatRoomId);

    /**
     * 특정 채팅방의 모든 메시지를 삭제합니다.
     * 
     * @param chatRoomId 채팅방 ID
     */
    void deleteByChatRoomId(String chatRoomId);
} 