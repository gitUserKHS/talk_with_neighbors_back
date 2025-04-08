package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatRoomType;
import lombok.Data;
import java.util.List;

@Data
public class CreateRoomRequest {
    private String name;
    private String type; // String 타입 유지
    private List<Long> participantIds;
    
    // 간단한 변환 로직만 유지
    public ChatRoomType getTypeEnum() {
        if (type == null) {
            return ChatRoomType.ONE_ON_ONE; // 기본값
        }
        try {
            return ChatRoomType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChatRoomType.ONE_ON_ONE; // 잘못된 값이면 기본값 반환
        }
    }
} 