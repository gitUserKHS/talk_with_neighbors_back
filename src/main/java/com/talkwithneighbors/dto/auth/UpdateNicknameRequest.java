package com.talkwithneighbors.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(
        @NotBlank(message = "닉네임을 입력해 주세요.")
        // Bean Validation counts UTF-16 code units. Allow up to 60 here so the
        // service can enforce the user-facing 30 Unicode code-point limit.
        @Size(min = 2, max = 60, message = "닉네임은 2자 이상 30자 이하여야 합니다.")
        String nickname
) {}
