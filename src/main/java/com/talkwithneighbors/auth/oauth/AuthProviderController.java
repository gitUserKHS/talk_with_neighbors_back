package com.talkwithneighbors.auth.oauth;

import com.talkwithneighbors.auth.email.EmailVerificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/public/auth")
public class AuthProviderController {
    private final OAuthProperties oauth;
    private final EmailVerificationService emailVerification;

    public AuthProviderController(OAuthProperties oauth, EmailVerificationService emailVerification) {
        this.oauth = oauth;
        this.emailVerification = emailVerification;
    }

    @GetMapping("/providers")
    public ProviderStatus providers() {
        return new ProviderStatus(List.of(
                new Provider("google", "Google", oauth.googleEnabled(), "/api/oauth2/authorization/google"),
                new Provider("kakao", "Kakao", oauth.kakaoEnabled(), "/api/oauth2/authorization/kakao")),
                emailVerification.availability());
    }

    public record ProviderStatus(
            List<Provider> providers,
            EmailVerificationService.Availability emailVerification) {}
    public record Provider(String id, String displayName, boolean enabled, String authorizationPath) {}
}
