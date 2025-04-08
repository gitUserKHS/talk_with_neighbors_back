package com.talkwithneighbors.dto.auth;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class RegisterRequestDto {
    private String email;
    private String username;
    private String password;
} 