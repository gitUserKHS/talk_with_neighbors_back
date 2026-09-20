package com.talkwithneighbors.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private Level originalLevel;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.INFO);
        logEvents.start();
        handlerLogger.addAppender(logEvents);
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logEvents);
        logEvents.stop();
        handlerLogger.setLevel(originalLevel);
    }

    @Test
    void maxUploadResponseExplainsVideoAndWholeRequestLimits() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(125L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNull(response.getBody().code());
        assertEquals(
                "한 파일은 최대 30MB(사진 10MB·동영상 30MB·일반 파일 25MB), "
                        + "첨부 요청 전체는 125MB를 넘을 수 없어요.",
                response.getBody().message()
        );
    }

    @Test
    void authExceptionCarriesCode() throws Exception {
        mockMvc.perform(get("/throw/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("이메일과 비밀번호를 확인해 주세요."));
    }

    @Test
    void exceptionWithoutCodeStillReturnsMessageAndNullCode() throws Exception {
        mockMvc.perform(get("/throw/chat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isEmpty())
                .andExpect(jsonPath("$.message").value("일정 시간을 입력해 줘."));
    }

    @Test
    void notFoundMatchingExceptionDoesNotLogAtError() throws Exception {
        mockMvc.perform(get("/throw/matching"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("매칭을 찾을 수 없어요."));

        assertFalse(logEvents.list.isEmpty(), "4xx should still leave an INFO line");
        assertTrue(logEvents.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)));
        assertTrue(logEvents.list.stream().anyMatch(event ->
                event.getLevel() == Level.INFO && event.getFormattedMessage().contains("매칭을 찾을 수 없어요.")));
    }

    @Test
    void serverSideMatchingExceptionLogsAtError() throws Exception {
        mockMvc.perform(get("/throw/matching-500"))
                .andExpect(status().isInternalServerError());

        assertTrue(logEvents.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    @Test
    void illegalArgumentMapsToBadRequest() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void accessDeniedMapsToForbidden() throws Exception {
        mockMvc.perform(get("/throw/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/throw/auth")
        void auth() {
            throw new AuthException("이메일과 비밀번호를 확인해 주세요.", HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS");
        }

        @GetMapping("/throw/chat")
        void chat() {
            throw new ChatException("일정 시간을 입력해 줘.", HttpStatus.BAD_REQUEST);
        }

        @GetMapping("/throw/matching")
        void matching() {
            throw new MatchingException("매칭을 찾을 수 없어요.", HttpStatus.NOT_FOUND);
        }

        @GetMapping("/throw/matching-500")
        void matchingServerError() {
            throw new MatchingException("매칭 저장에 실패했어요.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @GetMapping("/throw/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("bad input");
        }

        @GetMapping("/throw/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("no");
        }
    }
}
