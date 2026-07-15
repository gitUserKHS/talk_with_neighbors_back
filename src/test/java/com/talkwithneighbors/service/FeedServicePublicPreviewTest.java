package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.HiddenContentRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServicePublicPreviewTest {

    @Mock
    FeedPostRepository feedPostRepository;
    @Mock
    PostLikeRepository postLikeRepository;
    @Mock
    PostCommentRepository postCommentRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CompatibilityScoreService compatibilityScoreService;
    @Mock
    UserBlockRepository userBlockRepository;
    @Mock
    HiddenContentRepository hiddenContentRepository;
    @Mock
    DomainEventPublisher domainEventPublisher;

    @Test
    void creationIsPrivateByDefaultAndPersistsOnlyExplicitOptIn() {
        User author = new User();
        author.setId(7L);
        author.setUsername("neighbor");
        when(userRepository.findById(7L)).thenReturn(Optional.of(author));
        when(feedPostRepository.save(any(FeedPost.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateFeedPostRequest privateRequest = new CreateFeedPostRequest();
        privateRequest.setImageUrl("https://example.test/private.jpg");
        privateRequest.setCaption("Private unless explicitly shared");

        CreateFeedPostRequest publicRequest = new CreateFeedPostRequest();
        publicRequest.setCaption("Portfolio preview");
        publicRequest.setPublicPreview(true);
        FeedPostMedia publicMedia = new FeedPostMedia(
                "https://example.test/public.jpg",
                FeedMediaType.IMAGE
        );

        FeedPostDto privateResult = service().createPost(7L, privateRequest);
        FeedPostDto publicResult = service().createPost(7L, publicRequest, List.of(publicMedia));

        ArgumentCaptor<FeedPost> posts = ArgumentCaptor.forClass(FeedPost.class);
        verify(feedPostRepository, times(2)).save(posts.capture());
        assertThat(posts.getAllValues()).extracting(FeedPost::isPublicPreview)
                .containsExactly(false, true);
        assertThat(privateResult.isPublicPreview()).isFalse();
        assertThat(publicResult.isPublicPreview()).isTrue();
    }

    private FeedService service() {
        return new FeedService(
                feedPostRepository,
                postLikeRepository,
                postCommentRepository,
                userRepository,
                compatibilityScoreService,
                userBlockRepository,
                hiddenContentRepository,
                domainEventPublisher
        );
    }
}
