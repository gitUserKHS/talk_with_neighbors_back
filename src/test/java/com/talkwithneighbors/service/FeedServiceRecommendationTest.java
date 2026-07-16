package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.HiddenContentRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class FeedServiceRecommendationTest {

    @Mock FeedPostRepository feedPostRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock PostCommentRepository postCommentRepository;
    @Mock UserRepository userRepository;
    @Mock CompatibilityScoreService compatibilityScoreService;
    @Mock UserBlockRepository userBlockRepository;
    @Mock HiddenContentRepository hiddenContentRepository;
    @Mock DomainEventPublisher domainEventPublisher;

    private User viewer;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        viewer = user(1L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        feedService = new FeedService(
                feedPostRepository,
                postLikeRepository,
                postCommentRepository,
                userRepository,
                compatibilityScoreService,
                userBlockRepository,
                hiddenContentRepository,
                domainEventPublisher
        );
        when(userRepository.findById(viewer.getId())).thenReturn(Optional.of(viewer));
    }

    @Test
    void modeSelectsRecommendedNearbyOrLatestWithoutChangingThePageContract() {
        LocalDateTime now = LocalDateTime.now();
        FeedPost nearbyRelevant = post("near", user(2L, List.of("books"),
                37.5670, 126.9785, "서울특별시 중구"), List.of("books"), now.minusDays(1));
        FeedPost newestFar = post("new", user(3L, List.of("games"),
                35.1796, 129.0756, "부산광역시 중구"), List.of("games"), now);
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 500)))
                .thenReturn(new PageImpl<>(List.of(newestFar, nearbyRelevant), PageRequest.of(0, 500), 2));
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(newestFar, nearbyRelevant), PageRequest.of(0, 20), 2));

        var recommended = feedService.getFeed(viewer.getId(), FeedMode.RECOMMENDED, PageRequest.of(0, 20));
        var nearby = feedService.getFeed(viewer.getId(), FeedMode.NEARBY, PageRequest.of(0, 20));
        var latest = feedService.getFeed(viewer.getId(), FeedMode.LATEST, PageRequest.of(0, 20));

        assertThat(recommended.getContent()).extracting("id").containsExactly("near", "new");
        assertThat(nearby.getContent()).extracting("id").containsExactly("near", "new");
        assertThat(latest.getContent()).extracting("id").containsExactly("new", "near");
        assertThat(latest.getTotalElements()).isEqualTo(2);
        assertThat(nearby.getContent().get(0).getNeighborhoodName()).isEqualTo("서울특별시 중구");
        assertThat(nearby.getContent().get(0).getRecommendationReasons())
                .contains("NEARBY", "SHARED_INTERESTS");
        verify(postLikeRepository, never()).countByPost_Id(anyString());
        verify(postCommentRepository, never()).countByPost_Id(anyString());
        verify(postLikeRepository, never()).existsByPost_IdAndUser_Id(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void safetyFiltersRunBeforeRankingAndStableIdBreaksTimestampTies() {
        LocalDateTime sameTime = LocalDateTime.now().minusHours(1);
        User allowedAuthor = user(2L, List.of("books"), 37.56, 126.98, "서울특별시 중구");
        FeedPost allowedA = post("allowed-a", allowedAuthor, List.of("books"), sameTime);
        FeedPost allowedB = post("allowed-b", allowedAuthor, List.of("books"), sameTime);
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of(allowedB), PageRequest.of(0, 1), 2));
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(1, 1)))
                .thenReturn(new PageImpl<>(List.of(allowedA), PageRequest.of(1, 1), 2));

        var firstPage = feedService.getFeed(viewer.getId(), FeedMode.LATEST, PageRequest.of(0, 1));
        var secondPage = feedService.getFeed(viewer.getId(), FeedMode.LATEST, PageRequest.of(1, 1));

        assertThat(firstPage.getContent()).extracting("id").containsExactly("allowed-b");
        assertThat(secondPage.getContent()).extracting("id").containsExactly("allowed-a");
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        verify(feedPostRepository).findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 1));
        verify(feedPostRepository).findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(1, 1));
    }

    @Test
    void engagementQueriesStayConstantAsRecommendationCandidatesGrow() {
        LocalDateTime now = LocalDateTime.now();
        User author = user(2L, List.of("books"), 37.56, 126.98, "서울특별시 중구");
        List<FeedPost> candidates = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> post("post-" + index, author, List.of("books"), now.minusMinutes(index)))
                .toList();
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 500)))
                .thenReturn(new PageImpl<>(candidates, PageRequest.of(0, 500), candidates.size()));

        feedService.getFeed(viewer.getId(), FeedMode.RECOMMENDED, PageRequest.of(0, 20));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> postIds = ArgumentCaptor.forClass(List.class);
        verify(postLikeRepository, times(1)).countByPostIds(postIds.capture());
        verify(postCommentRepository, times(1)).countByPostIds(org.mockito.ArgumentMatchers.anyList());
        verify(postLikeRepository, times(1))
                .findLikedPostIds(org.mockito.ArgumentMatchers.eq(viewer.getId()),
                        org.mockito.ArgumentMatchers.anyList());
        assertThat(postIds.getValue()).hasSize(25);
        verify(postLikeRepository, never()).countByPost_Id(anyString());
        verify(postCommentRepository, never()).countByPost_Id(anyString());
    }

    @Test
    void recommendationTotalIsTheBoundedCandidateUniverseAndExtremeOffsetIsEmpty() {
        LocalDateTime now = LocalDateTime.now();
        User author = user(2L, List.of("books"), 37.56, 126.98, "서울특별시 중구");
        List<FeedPost> candidates = java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> post("bounded-" + index, author, List.of("books"), now.minusMinutes(index)))
                .toList();
        when(feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 500)))
                .thenReturn(new PageImpl<>(candidates, PageRequest.of(0, 500), 800));

        var result = feedService.getFeed(
                viewer.getId(), FeedMode.RECOMMENDED, PageRequest.of(Integer.MAX_VALUE, 50));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(500);
    }

    @Test
    void latestFeedRejectsAnUnsupportedDatabaseOffsetBeforeCallingTheRepository() {
        var result = feedService.getFeed(
                viewer.getId(), FeedMode.LATEST, PageRequest.of(Integer.MAX_VALUE, 50));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(feedPostRepository, never()).findVisibleFeed(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(PageRequest.class));
    }

    private User user(Long id, List<String> interests, double latitude, double longitude, String address) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setInterests(new ArrayList<>(interests));
        user.setLatitude(latitude);
        user.setLongitude(longitude);
        user.setAddress(address);
        return user;
    }

    private FeedPost post(
            String id,
            User author,
            List<String> interests,
            LocalDateTime createdAt
    ) {
        FeedPost post = new FeedPost();
        post.setId(id);
        post.setAuthor(author);
        post.setInterestTags(new ArrayList<>(interests));
        post.setImageUrl("https://example.test/" + id + ".jpg");
        post.setCreatedAt(createdAt);
        post.setUpdatedAt(createdAt);
        return post;
    }
}
