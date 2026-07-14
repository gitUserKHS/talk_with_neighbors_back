package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.publiccontent.PublicFeedMediaDto;
import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.security.AuthInterceptor;
import com.talkwithneighbors.security.PublicEndpoint;
import com.talkwithneighbors.service.PublicContentService;
import com.talkwithneighbors.service.SessionValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PublicContentControllerTest {

    @Mock
    PublicContentService publicContentService;

    @Mock
    SessionValidationService sessionValidationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PublicContentController controller = new PublicContentController(publicContentService);
        AuthInterceptor authInterceptor = new AuthInterceptor(sessionValidationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(authInterceptor)
                .build();
    }

    @Test
    void controllerIsExplicitlyMarkedPublic() {
        assertThat(PublicContentController.class.isAnnotationPresent(PublicEndpoint.class)).isTrue();
    }

    @Test
    void getsPublicFeedWithoutSessionAndCapsPageSize() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 20, 0);
        PublicFeedPostDto post = new PublicFeedPostDto(
                "post-1",
                "이웃",
                "/media/post.jpg",
                List.of(new PublicFeedMediaDto("/media/post.jpg", FeedMediaType.IMAGE, 0)),
                "Hello!",
                List.of("Books"),
                now,
                now,
                4,
                2,
                false
        );
        when(publicContentService.getFeed(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(post), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/public/feed")
                        .param("page", "-2")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("post-1"))
                .andExpect(jsonPath("$.content[0].authorDisplayName").value("이웃"))
                .andExpect(jsonPath("$.content[0].demo").value(false))
                .andExpect(jsonPath("$.content[0].authorProfileImage").doesNotExist())
                .andExpect(jsonPath("$.content[0].authorId").doesNotExist())
                .andExpect(jsonPath("$.content[0].likedByCurrentUser").doesNotExist())
                .andExpect(jsonPath("$.content[0].compatibilityScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].sharedInterests").doesNotExist());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(publicContentService).getFeed(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        verifyNoInteractions(sessionValidationService);
    }

    @Test
    void commentsAreNeverExposedByTheGuestApi() throws Exception {
        mockMvc.perform(get("/api/public/feed/post-1/comments"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(publicContentService, sessionValidationService);
    }

    @Test
    void getsPublicMeetupsWithoutSessionOrSensitiveFields() throws Exception {
        PublicMeetupDto meetup = new PublicMeetupDto(
                "meetup-1",
                "Book club",
                "Read together",
                List.of("Books"),
                8,
                3,
                false,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                120,
                LocalDateTime.of(2026, 7, 30, 23, 59),
                false
        );
        when(publicContentService.getMeetups(eq("book"), eq("books"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(meetup), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/public/meetups")
                        .param("keyword", "book")
                        .param("interest", "books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("meetup-1"))
                .andExpect(jsonPath("$.content[0].demo").value(false))
                .andExpect(jsonPath("$.content[0].location").doesNotExist())
                .andExpect(jsonPath("$.content[0].creatorUsername").doesNotExist())
                .andExpect(jsonPath("$.content[0].lastMessage").doesNotExist())
                .andExpect(jsonPath("$.content[0].joined").doesNotExist())
                .andExpect(jsonPath("$.content[0].waitlisted").doesNotExist())
                .andExpect(jsonPath("$.content[0].sharedInterests").doesNotExist());

        verifyNoInteractions(sessionValidationService);
    }
}
