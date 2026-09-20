package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.CreateCommentRequest;
import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.dto.feed.UpdateCommentRequest;
import com.talkwithneighbors.dto.feed.UpdateFeedPostRequest;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.domain.event.PostCommentedEvent;
import com.talkwithneighbors.domain.event.PostLikedEvent;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.HiddenContentRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.projection.PostEngagementCount;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceCrudTest {
    @Mock FeedPostRepository feedPostRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock PostCommentRepository postCommentRepository;
    @Mock UserRepository userRepository;
    @Mock CompatibilityScoreService compatibilityScoreService;
    @Mock UserBlockRepository userBlockRepository;
    @Mock HiddenContentRepository hiddenContentRepository;
    @Mock DomainEventPublisher domainEventPublisher;

    @InjectMocks FeedService feedService;

    private User author;
    private User otherUser;
    private FeedPost post;

    @BeforeEach
    void setUp() {
        author = user(1L, "author");
        otherUser = user(2L, "other");
        post = new FeedPost();
        post.setId("post-1");
        post.setAuthor(author);
        post.setImageUrl("/api/media/feed/original.webp");
        post.setMedia(new ArrayList<>(List.of(
                new FeedPostMedia("/api/media/feed/original.webp", FeedMediaType.IMAGE))));
        post.setCaption("before");
        post.setInterestTags(new ArrayList<>(List.of("walk")));
    }

    @Test
    void authorUpdatesMetadataWithoutChangingMedia() {
        when(userRepository.findById(author.getId())).thenReturn(Optional.of(author));
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(feedPostRepository.save(post)).thenReturn(post);

        var result = feedService.updatePost(
                author.getId(),
                post.getId(),
                new UpdateFeedPostRequest("  after  ", List.of(" cafe ", "cafe", "walk"), true));

        assertThat(result.getCaption()).isEqualTo("after");
        assertThat(result.getInterestTags()).containsExactly("cafe", "walk");
        assertThat(result.isPublicPreview()).isTrue();
        assertThat(result.getNeighborhoodName()).isEqualTo("서울특별시 중구");
        assertThat(result.getRecommendationReasons()).isEmpty();
        assertThat(result.getMedia()).singleElement()
                .extracting(media -> media.url())
                .isEqualTo("/api/media/feed/original.webp");
        verify(feedPostRepository).save(post);
    }

    @Test
    void nonAuthorCannotUpdatePost() {
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> feedService.updatePost(
                otherUser.getId(), post.getId(), new UpdateFeedPostRequest("blocked", null, null)))
                .isInstanceOfSatisfying(MatchingException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(feedPostRepository, never()).save(post);
    }

    @Test
    void authorUpdatesOwnCommentAndWhitespaceIsTrimmed() {
        PostComment comment = comment(author);
        when(userRepository.findById(author.getId())).thenReturn(Optional.of(author));
        when(postCommentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        when(postCommentRepository.save(comment)).thenReturn(comment);

        var result = feedService.updateComment(
                author.getId(), comment.getId(), new UpdateCommentRequest("  updated comment  "));

        assertThat(result.getContent()).isEqualTo("updated comment");
        verify(postCommentRepository).save(comment);
    }

    @Test
    void nonAuthorCannotUpdateComment() {
        PostComment comment = comment(author);
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        when(postCommentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> feedService.updateComment(
                otherUser.getId(), comment.getId(), new UpdateCommentRequest("blocked")))
                .isInstanceOfSatisfying(MatchingException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(postCommentRepository, never()).save(comment);
    }

    @Test
    void getPostForViewerReturnsRankedDtoWithEngagement() {
        post.setCreatedAt(LocalDateTime.now().minusHours(2));
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(hiddenContentRepository.findTargetIds(otherUser.getId(), SafetyTargetType.FEED_POST))
                .thenReturn(List.of());
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        when(postLikeRepository.countByPostIds(List.of(post.getId())))
                .thenReturn(List.of(new PostEngagementCount(post.getId(), 3L)));
        when(postCommentRepository.countByPostIds(List.of(post.getId())))
                .thenReturn(List.of(new PostEngagementCount(post.getId(), 2L)));
        when(postLikeRepository.findLikedPostIds(otherUser.getId(), List.of(post.getId())))
                .thenReturn(List.of(post.getId()));

        var result = feedService.getPostForViewer(otherUser.getId(), post.getId());

        assertThat(result.getId()).isEqualTo(post.getId());
        assertThat(result.getAuthorId()).isEqualTo(author.getId());
        assertThat(result.getLikeCount()).isEqualTo(3L);
        assertThat(result.getCommentCount()).isEqualTo(2L);
        assertThat(result.isLikedByCurrentUser()).isTrue();
        assertThat(result.getRecommendationReasons()).containsExactly("RECENT");
    }

    @Test
    void getPostForViewerRejectsHiddenPost() {
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(hiddenContentRepository.findTargetIds(otherUser.getId(), SafetyTargetType.FEED_POST))
                .thenReturn(List.of(post.getId()));

        assertThatThrownBy(() -> feedService.getPostForViewer(otherUser.getId(), post.getId()))
                .isInstanceOfSatisfying(MatchingException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("FEED_POST_NOT_FOUND");
                });

        verify(userRepository, never()).findById(otherUser.getId());
    }

    @Test
    void getPostForViewerRejectsBlockedViewer() {
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(userBlockRepository.existsBetween(otherUser.getId(), author.getId())).thenReturn(true);

        assertThatThrownBy(() -> feedService.getPostForViewer(otherUser.getId(), post.getId()))
                .isInstanceOfSatisfying(MatchingException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getCode()).isEqualTo("FEED_BLOCKED");
                });

        verify(hiddenContentRepository, never()).findTargetIds(otherUser.getId(), SafetyTargetType.FEED_POST);
    }

    @Test
    void jsonPostRejectsInternalMediaUrl() {
        when(userRepository.findById(author.getId())).thenReturn(Optional.of(author));
        CreateFeedPostRequest request = new CreateFeedPostRequest();
        request.setImageUrl("/uploads/profile/victim.webp");

        assertThatThrownBy(() -> feedService.createPost(author.getId(), request))
                .isInstanceOfSatisfying(MatchingException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        request.setImageUrl("/uploads/feed/victim.webp");
        assertThatThrownBy(() -> feedService.createPost(author.getId(), request))
                .isInstanceOfSatisfying(MatchingException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(feedPostRepository, never()).save(org.mockito.ArgumentMatchers.any(FeedPost.class));
    }

    @Test
    void deletingLegacyUrlPostDoesNotDeleteUnprovenInternalObject() {
        post.setImageUrl("/uploads/profile/victim.webp");
        post.setMedia(new ArrayList<>(List.of(
                new FeedPostMedia("/uploads/feed/victim.webp", FeedMediaType.IMAGE),
                new FeedPostMedia("/uploads/profile/victim.webp", FeedMediaType.IMAGE))));
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));

        feedService.deletePost(author.getId(), post.getId());

        verify(feedPostRepository).delete(post);
        verify(domainEventPublisher, never()).publish(
                org.mockito.ArgumentMatchers.any(MediaFilesDeletedEvent.class));
    }

    @Test
    void deletingMultipartPostPublishesOnlyOwnedFeedObjects() {
        FeedPostMedia owned = new FeedPostMedia(
                "/uploads/feed/owned.webp",
                FeedMediaType.IMAGE,
                "/uploads/feed/owned-thumbnail.webp",
                "image/webp",
                123L,
                640,
                480,
                null);
        FeedPostMedia foreignCategory = new FeedPostMedia(
                "/uploads/profile/victim.webp",
                FeedMediaType.IMAGE,
                "/uploads/profile/victim-thumbnail.webp",
                "image/webp",
                456L,
                640,
                480,
                null);
        post.setImageUrl(owned.getUrl());
        post.setMedia(new ArrayList<>(List.of(owned, foreignCategory)));
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));

        feedService.deletePost(author.getId(), post.getId());

        ArgumentCaptor<MediaFilesDeletedEvent> eventCaptor =
                ArgumentCaptor.forClass(MediaFilesDeletedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().mediaUrls())
                .containsExactly("/uploads/feed/owned.webp", "/uploads/feed/owned-thumbnail.webp");
        assertThat(eventCaptor.getValue().aggregateType()).isEqualTo("FeedPost");
        assertThat(eventCaptor.getValue().aggregateId()).isEqualTo(post.getId());
    }

    @Test
    void commentByNeighborPublishesPostCommentedEvent() {
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        when(postCommentRepository.save(any(PostComment.class))).thenAnswer(invocation -> {
            PostComment saved = invocation.getArgument(0);
            saved.setId("comment-9");
            return saved;
        });
        CreateCommentRequest request = new CreateCommentRequest();
        request.setContent("  nice   photo  ");

        feedService.addComment(otherUser.getId(), post.getId(), request);

        ArgumentCaptor<PostCommentedEvent> eventCaptor = ArgumentCaptor.forClass(PostCommentedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        PostCommentedEvent event = eventCaptor.getValue();
        assertThat(event.postId()).isEqualTo(post.getId());
        assertThat(event.commentId()).isEqualTo("comment-9");
        assertThat(event.postAuthorId()).isEqualTo(author.getId());
        assertThat(event.commenterId()).isEqualTo(otherUser.getId());
        assertThat(event.commenterName()).isEqualTo("other");
        assertThat(event.snippet()).isEqualTo("nice photo");
        assertThat(event.aggregateType()).isEqualTo("FeedPost");
    }

    @Test
    void commentByAuthorOrOnSystemPostPublishesNothing() {
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(userRepository.findById(author.getId())).thenReturn(Optional.of(author));
        when(postCommentRepository.save(any(PostComment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateCommentRequest request = new CreateCommentRequest();
        request.setContent("my own post");

        feedService.addComment(author.getId(), post.getId(), request);

        author.setAccountType(UserAccountType.SYSTEM);
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        feedService.addComment(otherUser.getId(), post.getId(), request);

        verify(domainEventPublisher, never()).publish(any(PostCommentedEvent.class));
    }

    @Test
    void firstLikePublishesPostLikedEventButRepeatedLikeDoesNot() {
        when(feedPostRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        AtomicBoolean liked = new AtomicBoolean(false);
        when(postLikeRepository.existsByPost_IdAndUser_Id(post.getId(), otherUser.getId()))
                .thenAnswer(invocation -> liked.get());
        when(postLikeRepository.save(any(PostLike.class))).thenAnswer(invocation -> {
            liked.set(true);
            return invocation.getArgument(0);
        });

        feedService.likePost(otherUser.getId(), post.getId());
        feedService.likePost(otherUser.getId(), post.getId());

        verify(postLikeRepository).save(any(PostLike.class));
        ArgumentCaptor<PostLikedEvent> eventCaptor = ArgumentCaptor.forClass(PostLikedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        PostLikedEvent event = eventCaptor.getValue();
        assertThat(event.postId()).isEqualTo(post.getId());
        assertThat(event.postAuthorId()).isEqualTo(author.getId());
        assertThat(event.likerId()).isEqualTo(otherUser.getId());
        assertThat(event.likerName()).isEqualTo("other");
    }

    private PostComment comment(User owner) {
        PostComment comment = new PostComment();
        comment.setId("comment-1");
        comment.setAuthor(owner);
        comment.setPost(post);
        comment.setContent("before");
        return comment;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setInterests(new ArrayList<>());
        user.setLatitude(37.5665);
        user.setLongitude(126.9780);
        user.setAddress("서울특별시 중구");
        user.setShowNeighborhood(true);
        return user;
    }
}
