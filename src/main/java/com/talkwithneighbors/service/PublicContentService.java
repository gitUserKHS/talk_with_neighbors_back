package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import com.talkwithneighbors.repository.projection.PostEngagementCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PublicContentService {
    private static final int RECOMMENDATION_CANDIDATE_LIMIT = 500;

    private final PublicFeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final PublicMeetupRepository meetupRepository;
    private final PostLikeRepository postLikeRepository;
    public PublicContentService(
            PublicFeedPostRepository feedPostRepository,
            PostCommentRepository postCommentRepository,
            PublicMeetupRepository meetupRepository,
            PostLikeRepository postLikeRepository
    ) {
        this.feedPostRepository = feedPostRepository;
        this.postCommentRepository = postCommentRepository;
        this.meetupRepository = meetupRepository;
        this.postLikeRepository = postLikeRepository;
    }

    public Page<PublicFeedPostDto> getFeed(Pageable pageable) {
        return getFeed(FeedMode.RECOMMENDED, null, pageable);
    }

    public Page<PublicFeedPostDto> getFeed(FeedMode mode, String region, Pageable pageable) {
        FeedMode effectiveMode = mode == null ? FeedMode.RECOMMENDED : mode;
        if (effectiveMode == FeedMode.LATEST) {
            if (pageable.getOffset() > Integer.MAX_VALUE) {
                return Page.empty(pageable);
            }
            Page<FeedPost> page = feedPostRepository.findPublicFeed(pageable);
            EngagementSnapshot engagement = loadEngagement(page.getContent());
            List<PublicFeedPostDto> content = page.getContent().stream()
                    .map(post -> toPublicDto(post, engagement))
                    .toList();
            return new PageImpl<>(content, pageable, page.getTotalElements());
        }

        LocalDateTime rankedAt = LocalDateTime.now();
        List<FeedPost> candidates = feedPostRepository.findPublicFeed(
                        PageRequest.of(0, RECOMMENDATION_CANDIDATE_LIMIT))
                .getContent();
        EngagementSnapshot engagement = loadEngagement(candidates);
        List<RankedPublicPost> ranked = candidates.stream()
                .map(post -> rank(post, region, rankedAt, engagement))
                .sorted((left, right) -> compareRankedPosts(effectiveMode, left, right))
                .toList();
        int start = safePageStart(pageable, ranked.size());
        int end = Math.min(start + pageable.getPageSize(), ranked.size());
        List<PublicFeedPostDto> content = ranked.subList(start, end).stream()
                .map(RankedPublicPost::dto)
                .toList();
        return new PageImpl<>(content, pageable, ranked.size());
    }

    public Page<PublicMeetupDto> getMeetups(String keyword, String interest, Pageable pageable) {
        String normalizedKeyword = normalize(keyword);
        String normalizedInterest = normalize(interest);
        Page<ChatRoom> page = meetupRepository.findPublicMeetups(
                        ChatRoomType.GROUP,
                        ChatRoomStatus.ACTIVE,
                        normalizedKeyword,
                        normalizedInterest,
                        pageable
                );
        return page.map(PublicMeetupDto::fromEntity);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private int safePageStart(Pageable pageable, int resultSize) {
        long offset = pageable.getOffset();
        return offset >= resultSize ? resultSize : Math.toIntExact(offset);
    }

    private PublicFeedPostDto toPublicDto(FeedPost post, EngagementSnapshot engagement) {
        return PublicFeedPostDto.fromEntity(
                post,
                engagement.likeCount(post.getId()),
                engagement.commentCount(post.getId())
        );
    }

    private RankedPublicPost rank(
            FeedPost post,
            String region,
            LocalDateTime rankedAt,
            EngagementSnapshot engagement
    ) {
        long likeCount = engagement.likeCount(post.getId());
        long commentCount = engagement.commentCount(post.getId());
        return new RankedPublicPost(
                post,
                PublicFeedPostDto.fromEntity(post, likeCount, commentCount),
                FeedRanking.publicSignals(post, likeCount, commentCount, region, rankedAt)
        );
    }

    private int compareRankedPosts(FeedMode mode, RankedPublicPost left, RankedPublicPost right) {
        int byScore = Double.compare(
                FeedRanking.publicScore(mode, right.signals()),
                FeedRanking.publicScore(mode, left.signals()));
        if (byScore != 0) {
            return byScore;
        }

        int byCreatedAt = Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())
                .compare(left.post().getCreatedAt(), right.post().getCreatedAt());
        if (byCreatedAt != 0) {
            return byCreatedAt;
        }
        return Comparator.nullsLast(Comparator.<String>reverseOrder())
                .compare(left.post().getId(), right.post().getId());
    }

    private record RankedPublicPost(
            FeedPost post,
            PublicFeedPostDto dto,
            FeedRanking.Signals signals
    ) {
    }

    private EngagementSnapshot loadEngagement(List<FeedPost> posts) {
        List<String> postIds = posts.stream()
                .map(FeedPost::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (postIds.isEmpty()) {
            return EngagementSnapshot.empty();
        }
        Map<String, Long> likeCounts = postLikeRepository.countByPostIds(postIds).stream()
                .collect(Collectors.toMap(PostEngagementCount::postId, PostEngagementCount::total));
        Map<String, Long> commentCounts = postCommentRepository.countByPostIds(postIds).stream()
                .collect(Collectors.toMap(PostEngagementCount::postId, PostEngagementCount::total));
        return new EngagementSnapshot(likeCounts, commentCounts);
    }

    private record EngagementSnapshot(
            Map<String, Long> likeCounts,
            Map<String, Long> commentCounts
    ) {
        private static EngagementSnapshot empty() {
            return new EngagementSnapshot(Map.of(), Map.of());
        }

        private long likeCount(String postId) {
            return likeCounts.getOrDefault(postId, 0L);
        }

        private long commentCount(String postId) {
            return commentCounts.getOrDefault(postId, 0L);
        }
    }
}
