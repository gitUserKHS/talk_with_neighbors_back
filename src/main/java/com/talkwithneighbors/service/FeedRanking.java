package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Stateless and request-scoped feed scoring rules.
 *
 * <p>No impression or location history is stored. Member recommendations use
 * 35% interest relevance, 25% proximity, 25% recency, and 15% engagement.
 * Nearby mode uses 65% proximity, 15% interest, 15% recency, and 5%
 * engagement. Public recommendations use only non-personal signals: 55%
 * recency, 35% engagement, and an optional 10% coarse-region match.</p>
 */
public final class FeedRanking {
    private static final double MEMBER_INTEREST_WEIGHT = 0.35;
    private static final double MEMBER_PROXIMITY_WEIGHT = 0.25;
    private static final double MEMBER_RECENCY_WEIGHT = 0.25;
    private static final double MEMBER_ENGAGEMENT_WEIGHT = 0.15;

    private static final double NEARBY_PROXIMITY_WEIGHT = 0.65;
    private static final double NEARBY_INTEREST_WEIGHT = 0.15;
    private static final double NEARBY_RECENCY_WEIGHT = 0.15;
    private static final double NEARBY_ENGAGEMENT_WEIGHT = 0.05;

    private static final double PUBLIC_RECENCY_WEIGHT = 0.55;
    private static final double PUBLIC_ENGAGEMENT_WEIGHT = 0.35;
    private static final double PUBLIC_REGION_WEIGHT = 0.10;

    private static final double PUBLIC_NEARBY_REGION_WEIGHT = 0.65;
    private static final double PUBLIC_NEARBY_RECENCY_WEIGHT = 0.25;
    private static final double PUBLIC_NEARBY_ENGAGEMENT_WEIGHT = 0.10;

    private static final double MAX_RELEVANT_DISTANCE_KM = 50.0;
    private static final double ENGAGEMENT_REFERENCE = Math.log1p(100.0);

    private FeedRanking() {
    }

    public static Signals memberSignals(
            User viewer,
            FeedPost post,
            long likeCount,
            long commentCount,
            LocalDateTime now
    ) {
        User author = post != null ? post.getAuthor() : null;
        InterestSignal interest = interestSignal(viewer, post);
        double region = canUseMemberLocation(viewer, author)
                ? coarseRegionSignal(viewer.getAddress(), author.getAddress())
                : 0.5;
        return new Signals(
                interest.score(),
                proximitySignal(viewer, author),
                recencySignal(post != null ? post.getCreatedAt() : null, now),
                engagementSignal(likeCount, commentCount),
                region,
                interest.shared()
        );
    }

    public static Signals publicSignals(
            FeedPost post,
            long likeCount,
            long commentCount,
            String coarseRegion,
            LocalDateTime now
    ) {
        User author = post != null ? post.getAuthor() : null;
        return new Signals(
                0.5,
                0.5,
                recencySignal(post != null ? post.getCreatedAt() : null, now),
                engagementSignal(likeCount, commentCount),
                requestedRegionSignal(coarseRegion, author),
                false
        );
    }

    public static double memberScore(FeedMode mode, Signals signals) {
        FeedMode effectiveMode = mode == null ? FeedMode.RECOMMENDED : mode;
        if (effectiveMode == FeedMode.LATEST) {
            return 0.0;
        }
        if (effectiveMode == FeedMode.NEARBY) {
            return (signals.proximity() * NEARBY_PROXIMITY_WEIGHT)
                    + (signals.interest() * NEARBY_INTEREST_WEIGHT)
                    + (signals.recency() * NEARBY_RECENCY_WEIGHT)
                    + (signals.engagement() * NEARBY_ENGAGEMENT_WEIGHT);
        }
        return (signals.interest() * MEMBER_INTEREST_WEIGHT)
                + (signals.proximity() * MEMBER_PROXIMITY_WEIGHT)
                + (signals.recency() * MEMBER_RECENCY_WEIGHT)
                + (signals.engagement() * MEMBER_ENGAGEMENT_WEIGHT);
    }

    public static double publicScore(FeedMode mode, Signals signals) {
        FeedMode effectiveMode = mode == null ? FeedMode.RECOMMENDED : mode;
        if (effectiveMode == FeedMode.LATEST) {
            return 0.0;
        }
        if (effectiveMode == FeedMode.NEARBY) {
            return (signals.region() * PUBLIC_NEARBY_REGION_WEIGHT)
                    + (signals.recency() * PUBLIC_NEARBY_RECENCY_WEIGHT)
                    + (signals.engagement() * PUBLIC_NEARBY_ENGAGEMENT_WEIGHT);
        }
        return (signals.recency() * PUBLIC_RECENCY_WEIGHT)
                + (signals.engagement() * PUBLIC_ENGAGEMENT_WEIGHT)
                + (signals.region() * PUBLIC_REGION_WEIGHT);
    }

    public static String visibleNeighborhood(User author) {
        if (author == null || !Boolean.TRUE.equals(author.getShowNeighborhood())
                || !hasUsableLocation(author)) {
            return null;
        }
        List<String> parts = addressParts(author.getAddress());
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" ", parts.subList(0, Math.min(2, parts.size())));
    }

    public static List<String> recommendationReasons(FeedMode mode, Signals signals) {
        FeedMode effectiveMode = mode == null ? FeedMode.RECOMMENDED : mode;
        if (effectiveMode == FeedMode.LATEST) {
            return List.of("RECENT");
        }

        List<String> reasons = new ArrayList<>();
        if (effectiveMode == FeedMode.NEARBY && signals.proximity() >= 0.6) {
            reasons.add("NEARBY");
        }
        if (signals.sharedInterest()) {
            reasons.add("SHARED_INTERESTS");
        }
        if (effectiveMode != FeedMode.NEARBY && signals.proximity() >= 0.6) {
            reasons.add("NEARBY");
        }
        if (signals.recency() >= 0.5) {
            reasons.add("RECENT");
        }
        if (signals.engagement() >= 0.35) {
            reasons.add("POPULAR");
        }
        return reasons.stream().limit(2).toList();
    }

    private static InterestSignal interestSignal(User viewer, FeedPost post) {
        Set<String> viewerInterests = normalize(viewer != null ? viewer.getInterests() : List.of());
        if (viewerInterests.isEmpty()) {
            return new InterestSignal(0.5, false);
        }

        Set<String> postInterests = normalize(post != null ? post.getInterestTags() : List.of());
        if (postInterests.isEmpty() && post != null && post.getAuthor() != null) {
            postInterests = normalize(post.getAuthor().getInterests());
        }
        if (postInterests.isEmpty()) {
            return new InterestSignal(0.5, false);
        }

        Set<String> shared = new LinkedHashSet<>(viewerInterests);
        shared.retainAll(postInterests);
        return new InterestSignal(
                Math.min(1.0, (double) shared.size() / viewerInterests.size()),
                !shared.isEmpty()
        );
    }

    private static double proximitySignal(User viewer, User author) {
        if (!canUseMemberLocation(viewer, author)) {
            return 0.5;
        }

        double region = coarseRegionSignal(viewer.getAddress(), author.getAddress());
        if (viewer.getLatitude() == null || viewer.getLongitude() == null
                || author.getLatitude() == null || author.getLongitude() == null) {
            return region;
        }

        double distanceKm = distanceKm(
                viewer.getLatitude(), viewer.getLongitude(),
                author.getLatitude(), author.getLongitude());
        double distance = Math.max(0.0, 1.0 - (distanceKm / MAX_RELEVANT_DISTANCE_KM));
        return (distance * 0.8) + (region * 0.2);
    }

    private static double recencySignal(LocalDateTime createdAt, LocalDateTime now) {
        if (createdAt == null || now == null) {
            return 0.0;
        }
        long wholeHours = Math.max(0L, Duration.between(createdAt, now).toHours());
        return 1.0 / (1.0 + (wholeHours / 24.0));
    }

    private static double engagementSignal(long likeCount, long commentCount) {
        double weightedEngagement = Math.max(0L, likeCount) + (Math.max(0L, commentCount) * 2.0);
        return Math.min(1.0, Math.log1p(weightedEngagement) / ENGAGEMENT_REFERENCE);
    }

    private static double requestedRegionSignal(String requestedRegion, User author) {
        List<String> requested = addressParts(requestedRegion);
        if (requested.isEmpty()) {
            return 0.5;
        }
        if (!isValidCoarseRegionRequest(requested)
                || author == null
                || !Boolean.TRUE.equals(author.getShowNeighborhood())
                || !hasUsableLocation(author)) {
            return 0.0;
        }
        List<String> authorRegion = addressParts(author.getAddress());
        if (authorRegion.size() < requested.size()) {
            return 0.0;
        }
        for (int index = 0; index < requested.size(); index++) {
            if (!requested.get(index).equals(authorRegion.get(index))) {
                return 0.0;
            }
        }
        return 1.0;
    }

    private static boolean canUseMemberLocation(User viewer, User author) {
        return viewer != null
                && author != null
                && Boolean.TRUE.equals(author.getShowNeighborhood())
                && hasUsableLocation(viewer)
                && hasUsableLocation(author);
    }

    private static boolean hasUsableLocation(User user) {
        if (user == null || user.getLatitude() == null || user.getLongitude() == null
                || user.getAddress() == null || user.getAddress().isBlank()) {
            return false;
        }
        double latitude = user.getLatitude();
        double longitude = user.getLongitude();
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0
                && !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
    }

    private static boolean isValidCoarseRegionRequest(List<String> parts) {
        if (parts.isEmpty() || parts.size() > 2 || !isTopLevelRegion(parts.get(0))) {
            return false;
        }
        return parts.size() == 1 || isLocalRegion(parts.get(1));
    }

    private static boolean isTopLevelRegion(String value) {
        return value.endsWith("특별시")
                || value.endsWith("광역시")
                || value.endsWith("특별자치시")
                || value.endsWith("특별자치도")
                || value.endsWith("도")
                || value.endsWith("시");
    }

    private static boolean isLocalRegion(String value) {
        return value.endsWith("시") || value.endsWith("군") || value.endsWith("구");
    }

    private static double coarseRegionSignal(String firstAddress, String secondAddress) {
        List<String> first = addressParts(firstAddress);
        List<String> second = addressParts(secondAddress);
        if (first.isEmpty() || second.isEmpty()) {
            return 0.5;
        }
        if (!first.get(0).equals(second.get(0))) {
            return 0.0;
        }
        if (first.size() > 1 && second.size() > 1 && first.get(1).equals(second.get(1))) {
            return 1.0;
        }
        return 0.65;
    }

    private static List<String> addressParts(String address) {
        String normalized = normalizeOne(address);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private static Set<String> normalize(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalizeOne(value);
            if (!item.isEmpty()) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private static String normalizeOne(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return earthRadiusKm * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    public record Signals(
            double interest,
            double proximity,
            double recency,
            double engagement,
            double region,
            boolean sharedInterest
    ) {
        public Signals(
                double interest,
                double proximity,
                double recency,
                double engagement,
                double region
        ) {
            this(interest, proximity, recency, engagement, region, false);
        }

        public Signals {
            interest = clamp(interest);
            proximity = clamp(proximity);
            recency = clamp(recency);
            engagement = clamp(engagement);
            region = clamp(region);
        }

        private static double clamp(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    private record InterestSignal(double score, boolean shared) {
    }
}
