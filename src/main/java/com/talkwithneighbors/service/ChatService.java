package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public interface ChatService {
    // 채팅방 생성
    @Transactional
    ChatRoom createRoom(String name, User creator, ChatRoomType type, List<Long> participantIds);
    
    // 채팅방 참여
    void joinRoom(String roomId, User user);
    
    // 채팅방 나가기
    void leaveRoom(String roomId, User user);
    
    // 메시지 전송
    Message sendMessage(ChatMessageDto messageDto);
    
    // 채팅방의 메시지 목록 조회
    List<Message> getMessages(String roomId, int page, int size);
    
    // 채팅방 목록 조회
    List<ChatRoom> getRooms(User user);
    
    // 특정 사용자와의 1:1 채팅방 찾기 또는 생성
    ChatRoom findOrCreateOneToOneRoom(User user1, User user2);
    
    // 랜덤 매칭 채팅방 생성
    ChatRoom createRandomMatchingRoom(User user);
    
    // 메시지 읽음 처리
    void markMessageAsRead(String messageId, User user);
    
    /**
     * 그룹 채팅방을 검색합니다.
     * @param keyword 검색 키워드 (채팅방 이름 또는 ID)
     * @return 검색된 그룹 채팅방 목록
     */
    List<ChatRoom> searchGroupRooms(String keyword);
    
    /**
     * 특정 사용자의 채팅방 목록을 조회합니다.
     * @param user 사용자
     * @return 채팅방 목록
     */
    List<ChatRoom> getRoomsByUser(User user);
    
    /**
     * 특정 채팅방을 조회합니다.
     * @param roomId 채팅방 ID
     * @param user 사용자
     * @return 채팅방
     */
    ChatRoom getRoom(String roomId, User user);
}