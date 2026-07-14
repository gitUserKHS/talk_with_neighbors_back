package com.talkwithneighbors.dto.auth;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
@Setter
@NoArgsConstructor
public class RegisterRequestDto {
    @NotBlank
    @Email
    private String email;
    @NotBlank
    @Size(min = 2, max = 30)
    private String username;
    @NotBlank
    @Size(min = 8, max = 72)
    private String password;
}
