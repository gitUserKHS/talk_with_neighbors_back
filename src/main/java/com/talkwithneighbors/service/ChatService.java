package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoomType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List; // createRoom 메서드의 participantIds 때문에 유지

public interface ChatService {
    // 채팅방 생성
    ChatRoomDto createRoom(String name, ChatRoomType type, String creatorId, List<String> participantNicknames);

    // 채팅방 ID로 채팅방 정보 조회
    ChatRoomDto getRoomById(String roomId, String userId);

    // 채팅방 참여
    void joinRoom(String roomId, String userId);

    // 채팅방 나가기
    void leaveRoom(String roomId, String userId);

    // 메시지 전송
    MessageDto sendMessage(String roomId, Long senderId, String content);

    // 특정 채팅방의 메시지 목록 조회 (페이징 처리)
    Page<MessageDto> getMessagesByRoomId(String roomId, String userId, Pageable pageable);

    // 사용자가 참여한 채팅방 목록 조회 (페이징 처리)
    Page<ChatRoomDto> getChatRoomsForUser(String userId, Pageable pageable);

    // 채팅방 검색 (페이징 처리)
    Page<ChatRoomDto> searchRooms(String query, ChatRoomType type, String userId, Pageable pageable);

    // 모든 채팅방 목록 조회 (관리자용, 페이징 처리)
    Page<ChatRoomDto> getAllRooms(Pageable pageable);

    // 채팅방 삭제
    void deleteRoom(String roomId);

    // 메시지 읽음 처리
    void markMessageAsRead(String messageId, String userId);

    // 채팅방에 사용자 추가
    void addUserToRoom(String roomId, String userId);

    // 채팅방에서 사용자 제거
    void removeUserFromRoom(String roomId, String userId);

    // 채팅방의 모든 메시지 읽음 처리
    void markAllMessagesInRoomAsRead(String roomId, String userIdString);
    
    // 채팅방 정보 업데이트
    ChatRoomDto updateRoom(String roomId, String name, ChatRoomType type);

    // === 매칭 관련 메서드 추가 ===
    // 두 사용자 간의 기존 1:1 채팅방 조회 (매칭 시스템용)
    ChatRoomDto findOneOnOneChatRoom(Long userId1, Long userId2);
    
    // 사용자가 1:1 채팅방을 가지고 있는 상대방 사용자 ID 목록 조회
    List<Long> getUsersWithOneOnOneChatRooms(Long userId);
}