package com.talkwithneighbors.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 방 전체 읽음 처리 결과를 채팅방 큐(/user/queue/chat/room/{roomId})로 알린다.
 * 메시지마다 참가자 수만큼 프레임을 보내던 방식 대신 방을 연 사용자당 참가자 한 명에게
 * {@code ROOM_READ} 프레임 하나만 보내며, 읽은 사람 본인은 UNREAD_COUNT_UPDATE로 갱신되므로 제외한다.
 * 저장이 끝난 뒤(afterCommit)에만 호출되어야 하고, 전송 실패는 이미 커밋된 읽음 상태를 되돌리지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatReadBroadcaster {

    /**
     * 채팅방 큐로 흘러가는 방 전체 읽음 프레임. 메시지·타이핑 프레임과 같은 큐를 쓰므로
     * 클라이언트는 {@code type}으로 구분한다.
     */
    public record RoomReadFrame(
            String type,
            String roomId,
            Long readByUserId,
            LocalDateTime readAt
    ) {
        public static final String TYPE = "ROOM_READ";

        public static RoomReadFrame of(String roomId, Long readByUserId, LocalDateTime readAt) {
            return new RoomReadFrame(TYPE, roomId, readByUserId, readAt);
        }
    }

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 읽은 사용자를 제외한 참가자 각각에게 ROOM_READ 프레임을 한 번씩 보낸다.
     *
     * @param roomId 채팅방 ID
     * @param readByUserId 방을 읽은 사용자 ID
     * @param readAt 읽음 처리 시각
     * @param participantIds 채팅방 참가자 ID 목록(읽은 사용자 포함 가능)
     */
    public void broadcastRoomRead(String roomId, Long readByUserId, LocalDateTime readAt, List<Long> participantIds) {
        RoomReadFrame frame = RoomReadFrame.of(roomId, readByUserId, readAt);
        String destination = "/queue/chat/room/" + roomId;
        try {
            for (Long participantId : participantIds) {
                if (participantId == null || participantId.equals(readByUserId)) {
                    continue;
                }
                messagingTemplate.convertAndSendToUser(participantId.toString(), destination, frame);
            }
        } catch (Exception exception) {
            log.error("Failed to broadcast room read. roomId={}, readByUserId={}", roomId, readByUserId, exception);
        }
    }
}
