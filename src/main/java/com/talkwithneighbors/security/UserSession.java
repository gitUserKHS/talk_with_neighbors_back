package com.talkwithneighbors.security;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserSession implements Serializable {
    // userId가 null인 경우 userIdStr에서 파싱해서 반환하는 getter 오버라이드
    public Long getUserId() {
        if (userId == null && userIdStr != null) {
            try {
                userId = Long.parseLong(userIdStr);
            } catch (NumberFormatException e) {
                userId = null;
            }
        }
        return userId;
    }
    private static final long serialVersionUID = -1554712050816392803L;  // Redis에 저장된 기존 값과 일치시킴
    
    private transient Long userId; // transient로 표시하여 직접 직렬화하지 않음
    private String username;
    private String email;
    private String nickname;
    
    // 직렬화를 위한 임시 필드
    private String userIdStr;

    // Jackson 역직렬화 시 userIdStr이 설정되면 userId도 자동으로 세팅
    @com.fasterxml.jackson.annotation.JsonSetter("userIdStr")
    public void setUserIdStr(String userIdStr) {
        this.userIdStr = userIdStr;
        if (userIdStr != null) {
            try {
                this.userId = Long.parseLong(userIdStr);
            } catch (NumberFormatException e) {
                this.userId = 0L;
            }
        }
    }

    // 생성자 추가 (userIdStr은 직렬화 용도로만 사용되므로 생성자에서 초기화하지 않음)
    public UserSession(Long userId, String username, String email, String nickname) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.nickname = nickname;
        this.userIdStr = userId != null ? userId.toString() : null;
    }

    public static UserSession of(Long userId, String username, String email, String nickname) {
        return new UserSession(userId, username, email, nickname);
    }
    
    // 직렬화 전 호출됨
    private void writeObject(ObjectOutputStream out) throws IOException {
        // userId를 String으로 변환하여 저장
        if (userId != null) {
            userIdStr = userId.toString();
        }
        out.defaultWriteObject();
    }
    
    // 역직렬화 후 호출됨
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // String을 Long으로 변환
        if (userIdStr != null) {
            try {
                userId = Long.parseLong(userIdStr);
            } catch (NumberFormatException e) {
                userId = 0L;
            }
        }
    }

    @Override
    public String toString() {
        return "UserSession{" +
                "userId=" + userId +
                ", userIdStr='" + userIdStr + '\'' +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", nickname='" + nickname + '\'' +
                '}';
    }
} 