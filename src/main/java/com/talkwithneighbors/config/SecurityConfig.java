package com.talkwithneighbors.config;

import com.talkwithneighbors.security.SessionAuthenticationFilter;
import com.talkwithneighbors.service.SessionValidationService;
import com.talkwithneighbors.auth.oauth.OAuthLoginFailureHandler;
import com.talkwithneighbors.auth.oauth.OAuthLoginSuccessHandler;
import com.talkwithneighbors.auth.oauth.TransientAuthorizedClientRepository;
import com.talkwithneighbors.auth.oauth.OAuthReturnToCaptureFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionValidationService sessionValidationService;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final OAuthLoginSuccessHandler oauthSuccessHandler;
    private final OAuthLoginFailureHandler oauthFailureHandler;
    private final TransientAuthorizedClientRepository authorizedClients;
    private final OAuthReturnToCaptureFilter oauthReturnToCaptureFilter;

    @Bean
    public static PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SessionAuthenticationFilter sessionAuthenticationFilter =
                new SessionAuthenticationFilter(sessionValidationService);

        http
            .cors(withDefaults())
            // SameSite=Lax plus the explicit CORS allowlist are the current CSRF
            // boundary. Add a synchronizer-token strategy before supporting any
            // cross-site client that requires SameSite=None.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/auth/login", "/api/auth/register", "/api/auth/logout",
                        "/api/auth/email-verifications", "/api/auth/email-verifications/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/check-duplicates").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/oauth2/authorization/**", "/api/login/oauth2/code/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/ws", "/ws/**", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/uploads/feed/**", "/uploads/profile/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/uploads/feed/**", "/uploads/profile/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)  // 동시 세션 제한
                .maxSessionsPreventsLogin(false)  // 새로운 로그인 시 이전 세션 만료
            )
            .formLogin(form -> form.disable())  // 폼 로그인 비활성화
            .httpBasic(basic -> basic.disable())  // HTTP Basic 인증 비활성화
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                    (request, response, exception) -> response.setStatus(401)))
            .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        if (clientRegistrations.getIfAvailable() != null) {
            http.addFilterBefore(
                    oauthReturnToCaptureFilter, OAuth2AuthorizationRequestRedirectFilter.class);
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/oauth2/authorization"))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/login/oauth2/code/*"))
                    .authorizedClientRepository(authorizedClients)
                    .successHandler(oauthSuccessHandler)
                    .failureHandler(oauthFailureHandler));
        }

        return http.build();
    }
}
