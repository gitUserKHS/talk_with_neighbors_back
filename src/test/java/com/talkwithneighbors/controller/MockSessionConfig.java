package com.talkwithneighbors.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static org.mockito.Mockito.mock;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@TestConfiguration
public class MockSessionConfig {
    @Bean
    public org.springframework.session.SessionRepository<org.springframework.session.Session> sessionRepository() {
        // Return a mock SessionRepository that always returns a mock Session
        return new org.springframework.session.SessionRepository<>() {
            @Override
            public org.springframework.session.Session createSession() {
                org.springframework.session.Session session = org.mockito.Mockito.mock(org.springframework.session.Session.class);
                org.mockito.Mockito.when(session.getId()).thenReturn("mock-session-id");
                org.mockito.Mockito.doNothing().when(session).setLastAccessedTime(org.mockito.Mockito.any(java.time.Instant.class));
                return session;
            }
            @Override
            public void save(org.springframework.session.Session session) {}
            @Override
            public org.springframework.session.Session findById(String id) {
                // Always return a mock Session for any id
                org.springframework.session.Session session = org.mockito.Mockito.mock(org.springframework.session.Session.class);
                // Optionally set up minimal stubbing for CSRF/lastAccessedTime if needed
                // Ensure the session returns a non-null ID and stubs setLastAccessedTime
                org.mockito.Mockito.when(session.getId()).thenReturn(id != null ? id : "mock-session-id");
                org.mockito.Mockito.doNothing().when(session).setLastAccessedTime(org.mockito.Mockito.any(java.time.Instant.class));
                return session;
            }
            @Override
            public void deleteById(String id) {}
        };
    }
}
