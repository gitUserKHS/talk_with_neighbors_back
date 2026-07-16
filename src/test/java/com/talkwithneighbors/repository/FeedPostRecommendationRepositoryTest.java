package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.HiddenContent;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserBlock;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=100"
})
@Import(TestConfig.class)
class FeedPostRecommendationRepositoryTest {

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    FeedPostRepository feedPostRepository;

    @Test
    void appliesHideAndBidirectionalBlockFiltersBeforeDeterministicPagination() {
        User viewer = persistUser("viewer", 37.5665, 126.9780, "서울특별시 중구");
        User visibleAuthor = persistUser("visible", 37.5670, 126.9785, "서울특별시 중구");
        User blockedByViewer = persistUser("blocked-by-viewer", 37.5680, 126.9790, "서울특별시 중구");
        User blockingViewer = persistUser("blocking-viewer", 37.5690, 126.9800, "서울특별시 중구");
        LocalDateTime tie = LocalDateTime.of(2026, 7, 16, 12, 0);

        persistPost("visible-a", visibleAuthor, tie.minusHours(1));
        persistPost("visible-b", visibleAuthor, tie.minusHours(1));
        persistPost("hidden-new", visibleAuthor, tie);
        persistPost("blocked-outgoing-new", blockedByViewer, tie);
        persistPost("blocked-incoming-new", blockingViewer, tie);
        entityManager.persist(new HiddenContent(viewer, SafetyTargetType.FEED_POST, "hidden-new"));
        entityManager.persist(new UserBlock(viewer, blockedByViewer));
        entityManager.persist(new UserBlock(blockingViewer, viewer));
        entityManager.flush();
        entityManager.clear();

        Page<FeedPost> first = feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 1));
        Page<FeedPost> second = feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(1, 1));
        assertThat(first.getContent()).extracting(FeedPost::getId).containsExactly("visible-b");
        assertThat(second.getContent()).extracting(FeedPost::getId).containsExactly("visible-a");
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
    }

    @Test
    void batchesEveryLazyFeedCollectionInsteadOfRunningOneQueryPerCandidate() {
        User viewer = persistUser("query-viewer", 37.5665, 126.9780, "서울특별시 중구");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        IntStream.range(0, 25).forEach(index -> {
            User author = persistUser(
                    "query-author-" + index,
                    37.5670 + (index * 0.00001),
                    126.9785,
                    "서울특별시 중구");
            persistPost("query-post-" + String.format("%02d", index), author,
                    createdAt.minusMinutes(index));
        });
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManager()
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        statistics.clear();

        Page<FeedPost> page = feedPostRepository.findVisibleFeed(
                viewer.getId(), SafetyTargetType.FEED_POST, PageRequest.of(0, 25));
        assertThat(page.getTotalElements()).isEqualTo(25);
        int loadedValues = page.getContent().stream()
                .mapToInt(post -> post.getAuthor().getUsername().length()
                        + post.getAuthor().getInterests().size()
                        + post.getMedia().size()
                        + post.getInterestTags().size())
                .sum();

        assertThat(loadedValues).isPositive();
        assertThat(statistics.getPrepareStatementCount())
                .as("content, count, and batched collection reads must stay bounded")
                .isBetween(4L, 8L);
    }

    private User persistUser(
            String username,
            double latitude,
            double longitude,
            String address
    ) {
        User user = new User();
        user.setEmail(username + "@example.test");
        user.setUsername(username);
        user.setPassword("encoded-password");
        user.setLatitude(latitude);
        user.setLongitude(longitude);
        user.setAddress(address);
        user.setShowNeighborhood(true);
        user.setInterests(new ArrayList<>(List.of("books", "walking")));
        return entityManager.persist(user);
    }

    private FeedPost persistPost(String id, User author, LocalDateTime createdAt) {
        FeedPost post = new FeedPost();
        post.setId(id);
        post.setAuthor(author);
        post.setImageUrl("https://example.test/" + id + ".jpg");
        post.setMedia(new ArrayList<>(List.of(
                new FeedPostMedia("https://example.test/" + id + ".jpg", FeedMediaType.IMAGE))));
        post.setCaption(id);
        post.setInterestTags(new ArrayList<>(List.of("books", "coffee")));
        post.setCreatedAt(createdAt);
        post.setUpdatedAt(createdAt);
        return entityManager.persist(post);
    }
}
