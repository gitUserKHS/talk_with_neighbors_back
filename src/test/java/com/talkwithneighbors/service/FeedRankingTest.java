package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class FeedRankingTest {

    @Test
    void memberWeightsAreExplicitAndBounded() {
        FeedRanking.Signals signals = new FeedRanking.Signals(1.0, 0.8, 0.6, 0.4, 0.0);

        assertThat(FeedRanking.memberScore(FeedMode.RECOMMENDED, signals))
                .isCloseTo(0.76, offset(0.000001));
        assertThat(FeedRanking.memberScore(FeedMode.NEARBY, signals))
                .isCloseTo(0.78, offset(0.000001));
    }

    @Test
    void publicWeightsUseOnlyRecencyEngagementAndOptionalRegion() {
        FeedRanking.Signals signals = new FeedRanking.Signals(1.0, 1.0, 0.8, 0.6, 1.0);

        assertThat(FeedRanking.publicScore(FeedMode.RECOMMENDED, signals))
                .isCloseTo(0.75, offset(0.000001));
        assertThat(FeedRanking.publicScore(FeedMode.NEARBY, signals))
                .isCloseTo(0.91, offset(0.000001));
    }

    @Test
    void latestModeIgnoresEveryRecommendationSignal() {
        FeedRanking.Signals strongest = new FeedRanking.Signals(1.0, 1.0, 1.0, 1.0, 1.0);
        FeedRanking.Signals weakest = new FeedRanking.Signals(0.0, 0.0, 0.0, 0.0, 0.0);

        assertThat(FeedRanking.memberScore(FeedMode.LATEST, strongest)).isZero();
        assertThat(FeedRanking.memberScore(FeedMode.LATEST, weakest)).isZero();
        assertThat(FeedRanking.publicScore(FeedMode.LATEST, strongest)).isZero();
        assertThat(FeedRanking.publicScore(FeedMode.LATEST, weakest)).isZero();
    }

    @Test
    void memberSignalsRewardSharedInterestAndNearbyAuthorWithoutTrackingHistory() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User viewer = user(1L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        User nearAuthor = user(2L, List.of("walk"), 37.5670, 126.9785, "서울특별시 중구");
        User farAuthor = user(3L, List.of("books"), 35.1796, 129.0756, "부산광역시 중구");

        FeedRanking.Signals near = FeedRanking.memberSignals(
                viewer, post("near", nearAuthor, List.of("books"), now.minusHours(2)), 0, 0, now);
        FeedRanking.Signals far = FeedRanking.memberSignals(
                viewer, post("far", farAuthor, List.of("games"), now.minusHours(2)), 100, 0, now);

        assertThat(near.interest()).isEqualTo(1.0);
        assertThat(near.proximity()).isGreaterThan(0.95);
        assertThat(far.proximity()).isLessThan(0.1);
        assertThat(FeedRanking.memberScore(FeedMode.RECOMMENDED, near))
                .isGreaterThan(FeedRanking.memberScore(FeedMode.RECOMMENDED, far));
    }

    @Test
    void hiddenNeighborhoodMakesLocationANeutralSignal() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User viewer = user(1L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        User author = user(2L, List.of("books"), 35.1796, 129.0756, "부산광역시 중구");
        author.setShowNeighborhood(false);

        FeedRanking.Signals signals = FeedRanking.memberSignals(
                viewer, post("private-location", author, List.of("books"), now), 0, 0, now);

        assertThat(signals.proximity()).isEqualTo(0.5);
        assertThat(FeedRanking.visibleNeighborhood(author)).isNull();
        assertThat(FeedRanking.recommendationReasons(FeedMode.NEARBY, signals))
                .doesNotContain("NEARBY");
    }

    @Test
    void nullableNeighborhoodConsentFailsClosed() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User viewer = user(1L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        User author = user(2L, List.of("books"), 37.5670, 126.9785, "서울특별시 중구");
        author.setShowNeighborhood(null);
        FeedPost post = post("nullable-consent", author, List.of("books"), now);

        FeedRanking.Signals member = FeedRanking.memberSignals(viewer, post, 0, 0, now);
        FeedRanking.Signals guest = FeedRanking.publicSignals(post, 0, 0, "서울특별시 중구", now);

        assertThat(member.proximity()).isEqualTo(0.5);
        assertThat(member.region()).isEqualTo(0.5);
        assertThat(guest.region()).isZero();
        assertThat(FeedRanking.visibleNeighborhood(author)).isNull();
    }

    @Test
    void zeroCoordinateSentinelNeverBecomesLocationEvidence() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User viewer = user(1L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        User incompleteAuthor = user(2L, List.of("books"), 0.0, 0.0, "서울특별시 중구");
        FeedPost post = post("incomplete-location", incompleteAuthor, List.of("books"), now);

        FeedRanking.Signals member = FeedRanking.memberSignals(viewer, post, 0, 0, now);
        FeedRanking.Signals guest = FeedRanking.publicSignals(post, 0, 0, "서울특별시 중구", now);

        assertThat(member.proximity()).isEqualTo(0.5);
        assertThat(member.region()).isEqualTo(0.5);
        assertThat(guest.region()).isZero();
        assertThat(FeedRanking.visibleNeighborhood(incompleteAuthor)).isNull();
        assertThat(FeedRanking.recommendationReasons(FeedMode.NEARBY, member))
                .doesNotContain("NEARBY");
    }

    @Test
    void publicRegionAcceptsOnlyExactOneOrTwoTokenAdministrativePrefixes() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User author = user(2L, List.of("books"), 37.5665, 126.9780,
                "서울특별시 중구 세종대로 110");
        FeedPost post = post("region", author, List.of("books"), now);

        assertThat(FeedRanking.publicSignals(post, 0, 0, "  서울특별시   중구  ", now).region())
                .isEqualTo(1.0);
        assertThat(FeedRanking.publicSignals(post, 0, 0, "서울특별시", now).region())
                .isEqualTo(1.0);
        assertThat(FeedRanking.publicSignals(post, 0, 0, null, now).region())
                .isEqualTo(0.5);
        assertThat(FeedRanking.publicSignals(post, 0, 0, "중구", now).region()).isZero();
        assertThat(FeedRanking.publicSignals(post, 0, 0, "세종대로", now).region()).isZero();
        assertThat(FeedRanking.publicSignals(post, 0, 0,
                "서울특별시 중구 세종대로", now).region()).isZero();
        assertThat(FeedRanking.publicSignals(post, 0, 0, "서울특별시 강남구", now).region())
                .isZero();
    }

    @Test
    void publicSignalsDoNotReadMemberInterestOrViewerLocation() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User firstAuthor = user(2L, List.of("books"), 37.5665, 126.9780, "서울특별시 중구");
        User secondAuthor = user(3L, List.of("games"), 35.1796, 129.0756, "부산광역시 중구");
        FeedPost first = post("first", firstAuthor, List.of("books"), now.minusHours(2));
        FeedPost second = post("second", secondAuthor, List.of("games"), now.minusHours(2));

        FeedRanking.Signals firstSignals = FeedRanking.publicSignals(first, 3, 2, null, now);
        FeedRanking.Signals secondSignals = FeedRanking.publicSignals(second, 3, 2, null, now);

        assertThat(firstSignals).isEqualTo(secondSignals);
    }

    @Test
    void neutralInterestNeverProducesAFalseSharedInterestReason() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);
        User viewer = user(1L, List.of(), 37.5665, 126.9780, "서울특별시 중구");
        User author = user(2L, List.of(), 37.5670, 126.9785, "서울특별시 중구");
        FeedRanking.Signals neutral = FeedRanking.memberSignals(
                viewer, post("neutral", author, List.of(), now.minusDays(3)), 0, 0, now);

        assertThat(neutral.interest()).isEqualTo(0.5);
        assertThat(neutral.sharedInterest()).isFalse();
        assertThat(FeedRanking.recommendationReasons(FeedMode.RECOMMENDED, neutral))
                .doesNotContain("SHARED_INTERESTS");
    }

    private User user(Long id, List<String> interests, double latitude, double longitude, String address) {
        User user = new User();
        user.setId(id);
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
