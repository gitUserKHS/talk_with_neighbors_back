package com.talkwithneighbors.dto;

import java.time.LocalDateTime;

/**
 * 채팅방 큐(/user/queue/chat/room/{roomId})로 흘러가는 타이핑 신호 프레임.
 * 메시지 프레임과 같은 큐를 쓰므로 클라이언트는 {@code type}으로 구분한다.
 * 저장하지 않는 순수 릴레이 신호이며 {@code expiresAt}은 서버 기준 표시 만료 힌트다.
 */
public record TypingSignalDto(
        String type,
        String roomId,
        Long userId,
        String senderName,
        LocalDateTime expiresAt
) {
    public static final String TYPE = "TYPING";
    public static final int VISIBLE_SECONDS = 4;

    public static TypingSignalDto of(String roomId, Long userId, String senderName, LocalDateTime now) {
        return new TypingSignalDto(TYPE, roomId, userId, senderName, now.plusSeconds(VISIBLE_SECONDS));
    }
}
