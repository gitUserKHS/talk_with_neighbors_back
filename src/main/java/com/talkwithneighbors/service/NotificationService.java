package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.Message;

/**
 * 실시간 알림을 처리하는 서비스 인터페이스
 */
public interface NotificationService {
    
    /**
     * 새 메시지 알림을 채팅방 참여자들에게 전송합니다.
     * 
     * @param message 새로 생성된 메시지
     * @param chatRoom 메시지가 속한 채팅방
     * @param senderId 메시지 발신자 ID (알림을 받지 않음)
     */
    void sendNewMessageNotification(Message message, ChatRoom chatRoom, Long senderId);
    
    /**
     * 메시지 읽음 상태 변경 알림을 채팅방 참여자들에게 전송합니다.
     * 
     * @param messageId 읽음 상태가 변경된 메시지 ID
     * @param chatRoomId 채팅방 ID
     * @param readByUserId 메시지를 읽은 사용자 ID
     */
    void sendMessageReadStatusUpdate(String messageId, String chatRoomId, Long readByUserId);
    
    /**
     * 채팅방의 읽지 않은 메시지 수 업데이트 알림을 전송합니다.
     * 
     * @param chatRoomId 채팅방 ID
     * @param userId 사용자 ID
     * @param unreadCount 읽지 않은 메시지 수
     */
    void sendUnreadCountUpdate(String chatRoomId, Long userId, long unreadCount);
} 