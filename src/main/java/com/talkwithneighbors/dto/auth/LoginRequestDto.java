package com.talkwithneighbors.dto.auth;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
@Setter
public class LoginRequestDto {
    @NotBlank
    @Email
    private String email;
    @NotBlank
    @Size(max = 72)
    private String password;
}
