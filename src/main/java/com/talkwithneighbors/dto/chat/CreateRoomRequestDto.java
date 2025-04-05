package com.talkwithneighbors.dto.chat;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CreateRoomRequestDto {
    private List<String> participants;
} 