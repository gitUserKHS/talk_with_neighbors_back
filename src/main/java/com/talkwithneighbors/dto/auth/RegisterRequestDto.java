package com.talkwithneighbors.dto.auth;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequestDto {
    private String email;
    private String username;
    private String password;
} 