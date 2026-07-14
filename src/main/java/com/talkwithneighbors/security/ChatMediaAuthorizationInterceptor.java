package com.talkwithneighbors.security;

import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.service.media.storage.MediaStoragePath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

public class ChatMediaAuthorizationInterceptor implements HandlerInterceptor {

    private static final String CHAT_KEY_PREFIX = "chat/";

    private final MessageRepository messageRepository;

    public ChatMediaAuthorizationInterceptor(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        preventSharedCaching(response);

        Object sessionAttribute = request.getAttribute("USER_SESSION");
        if (!(sessionAttribute instanceof UserSession userSession)
                || userSession.getUserId() == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }

        Optional<String> relativeKey = MediaStoragePath.fromPublicUrl(pathWithinApplication(request));
        if (relativeKey.isEmpty() || !relativeKey.get().startsWith(CHAT_KEY_PREFIX)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return false;
        }

        String mediaUrl = MediaStoragePath.publicUrl(relativeKey.get());
        if (messageRepository.countAccessibleChatAttachments(mediaUrl, userSession.getUserId()) == 0) {
            // Do not reveal whether an attachment exists in a room the caller cannot access.
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return false;
        }

        return true;
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private void preventSharedCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
