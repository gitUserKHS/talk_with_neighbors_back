package com.talkwithneighbors.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketNotification<T> {
    private String type;
    private T data;
    private String message; // 간단한 알림 메시지 (옵션)
    private String navigateTo; // 클릭 시 이동할 경로 (옵션)

    // data, message, navigateTo 중 필요한 것만 사용하는 생성자도 추가할 수 있습니다.
    public WebSocketNotification(String type, T data) {
        this.type = type;
        this.data = data;
    }

    public WebSocketNotification(String type, T data, String message) {
        this.type = type;
        this.data = data;
        this.message = message;
    }
} 