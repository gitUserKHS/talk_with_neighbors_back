package com.talkwithneighbors.dto.auth;

import com.talkwithneighbors.dto.UserDto;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;

@Getter
@Setter
@AllArgsConstructor
public class LoginResponseDto {
    private UserDto user;
} 