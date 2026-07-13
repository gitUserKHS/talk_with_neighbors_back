package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.FeedService;
import com.talkwithneighbors.service.MediaStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedControllerTest {

    @Mock
    FeedService feedService;

    @Mock
    MediaStorageService mediaStorageService;

    @Mock
    MultipartFile file;

    @Test
    void removesMediaAndThumbnailWhenPostPersistenceFails() {
        FeedPostMedia media = new FeedPostMedia(
                "/uploads/feed/media.mp4",
                FeedMediaType.VIDEO,
                "/uploads/feed/media-thumbnail.webp",
                "video/mp4",
                10L,
                640,
                360,
                1.5
        );
        CreateFeedPostRequest request = new CreateFeedPostRequest();
        UserSession session = UserSession.of(7L, "user", "user@example.test", "neighbor");
        when(mediaStorageService.storePostMedia(List.of(file))).thenReturn(List.of(media));
        when(feedService.createPost(7L, request, List.of(media)))
                .thenThrow(new IllegalStateException("database unavailable"));
        FeedController controller = new FeedController(feedService, mediaStorageService);

        assertThatThrownBy(() -> controller.createPostWithMedia(request, List.of(file), session))
                .isInstanceOf(IllegalStateException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> urls = ArgumentCaptor.forClass(List.class);
        verify(mediaStorageService).deletePostMedia(urls.capture());
        assertThat(urls.getValue()).containsExactly(
                "/uploads/feed/media.mp4",
                "/uploads/feed/media-thumbnail.webp"
        );
    }
}
