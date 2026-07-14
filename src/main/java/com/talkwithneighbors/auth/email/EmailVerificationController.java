package com.talkwithneighbors.auth.email;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/auth/email-verifications")
public class EmailVerificationController {
    private final EmailVerificationService service;
    private final EmailVerificationCookieFactory cookies;

    public EmailVerificationController(EmailVerificationService service, EmailVerificationCookieFactory cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping
    public ResponseEntity<EmailVerificationService.IssuedChallenge> request(
            @Valid @RequestBody RequestCodeRequest request) {
        return ResponseEntity.accepted().body(service.requestCode(request.email()));
    }

    @PostMapping("/{challengeId}/confirm")
    public ResponseEntity<ConfirmationResponse> confirm(
            @PathVariable String challengeId,
            @Valid @RequestBody ConfirmCodeRequest request) {
        EmailVerificationService.ConfirmedProof proof = service.confirm(challengeId, request.code());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.proof(proof.value(), proof.ttl()).toString())
                .body(new ConfirmationResponse(true, proof.ttl().toSeconds()));
    }

    @PostMapping("/{challengeId}/resend")
    public ResponseEntity<EmailVerificationService.IssuedChallenge> resend(@PathVariable String challengeId) {
        return ResponseEntity.accepted().body(service.resend(challengeId));
    }

    public record RequestCodeRequest(@NotBlank @Email String email) {}
    public record ConfirmCodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
    public record ConfirmationResponse(boolean verified, long proofExpiresInSeconds) {}
}
