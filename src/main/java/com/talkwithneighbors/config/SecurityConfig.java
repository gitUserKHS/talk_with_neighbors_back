package com.talkwithneighbors.config;

import com.talkwithneighbors.security.SessionAuthenticationFilter;
import com.talkwithneighbors.service.SessionValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SessionValidationService sessionValidationService;

    @Bean
    public PasswordEncoder passwordEncoder() {
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
                        "/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/check-duplicates").permitAll()
                .requestMatchers("/api/public/**").permitAll()
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

        return http.build();
    }
}
