package com.talkwithneighbors.security;

import com.talkwithneighbors.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMediaAuthorizationInterceptorTest {

    @Mock
    MessageRepository messageRepository;

    private ChatMediaAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ChatMediaAuthorizationInterceptor(messageRepository);
    }

    @Test
    void allowsAnAttachmentOnlyWhenTheUserParticipatesInItsRoom() {
        MockHttpServletRequest request = requestFor("/uploads/chat/photo.webp", 7L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/photo.webp", 7L
        )).thenReturn(1L);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("private", "no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
    }

    @Test
    void hidesAttachmentsFromNonParticipants() {
        MockHttpServletRequest request = requestFor("/uploads/chat/photo.webp", 8L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/photo.webp", 8L
        )).thenReturn(0L);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void rejectsMissingAuthenticationBeforeLookingUpAttachments() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/uploads/chat/photo.webp"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(messageRepository, never()).countAccessibleChatAttachments(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()
        );
    }

    private MockHttpServletRequest requestFor(String path, Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute("USER_SESSION", UserSession.of(
                userId, "neighbor", "neighbor@example.test", "Neighbor"
        ));
        return request;
    }
}
