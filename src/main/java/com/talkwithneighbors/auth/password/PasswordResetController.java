package com.talkwithneighbors.auth.password;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public record ResetRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    public record ConfirmRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 6, max = 6) String code,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {
    }

    /** 로그인 화면이 링크를 보여줄지 판단하는 데 쓴다. */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Boolean>> availability() {
        return ResponseEntity.ok(Map.of("enabled", passwordResetService.isEnabled()));
    }

    /**
     * 가입되지 않은 주소여도 202를 돌려준다. 계정 존재 여부를 알려 주지 않기 위해서다.
     */
    @PostMapping
    public ResponseEntity<Void> request(@Valid @RequestBody ResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmRequest request) {
        passwordResetService.confirmReset(request.email(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
