package com.talkwithneighbors.controller;

import com.talkwithneighbors.config.TestSecurityConfig;
import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.dto.feed.PostCommentDto;
import com.talkwithneighbors.dto.feed.UpdateFeedPostRequest;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.FeedService;
import com.talkwithneighbors.service.MediaStorageService;
import com.talkwithneighbors.service.SessionValidationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FeedController.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, ChatExceptionHandler.class})
class FeedControllerWebTest {
    private static final String SESSION_ID = "feed-session";

    @Autowired MockMvc mockMvc;
    @MockBean FeedService feedService;
    @MockBean MediaStorageService mediaStorageService;
    @MockBean SessionValidationService sessionValidationService;
    @MockBean org.springframework.session.SessionRepository<?> sessionRepository;
    @MockBean MessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        when(sessionValidationService.validateSession(SESSION_ID))
                .thenReturn(UserSession.of(1L, "author", "author@example.test", "author"));
    }

    @Test
    void patchPostUsesAuthenticatedAuthorAndReturnsUpdatedPost() throws Exception {
        FeedPostDto response = new FeedPostDto();
        response.setId("post-1");
        response.setCaption("updated");
        when(feedService.updatePost(eq(1L), eq("post-1"), any())).thenReturn(response);

        mockMvc.perform(patch("/api/feed/{postId}", "post-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caption":"updated","tags":["walk"],"publicPreview":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("post-1"))
                .andExpect(jsonPath("$.caption").value("updated"));

        ArgumentCaptor<UpdateFeedPostRequest> requestCaptor =
                ArgumentCaptor.forClass(UpdateFeedPostRequest.class);
        verify(feedService).updatePost(eq(1L), eq("post-1"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().interestTags()).containsExactly("walk");
    }

    @Test
    void getFeedForwardsModeAndSanitizesPageBounds() throws Exception {
        when(feedService.getFeed(eq(1L), eq(FeedMode.NEARBY), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 50)));

        mockMvc.perform(get("/api/feed")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .param("mode", "NEARBY")
                        .param("page", "-3")
                        .param("size", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(feedService).getFeed(eq(1L), eq(FeedMode.NEARBY), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void rejectsUnknownFeedMode() throws Exception {
        mockMvc.perform(get("/api/feed")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .param("mode", "POPULAR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchCommentRejectsBlankContentBeforeService() throws Exception {
        mockMvc.perform(patch("/api/feed/comments/{commentId}", "comment-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchCommentReturnsUpdatedComment() throws Exception {
        PostCommentDto response = new PostCommentDto();
        response.setId("comment-1");
        response.setContent("updated");
        when(feedService.updateComment(eq(1L), eq("comment-1"), any())).thenReturn(response);

        mockMvc.perform(patch("/api/feed/comments/{commentId}", "comment-1")
                        .cookie(new Cookie("TWN_SESSION", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("updated"));
    }
}
