package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.CreateCommentRequest;
import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.feed.PostCommentDto;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedService {
    private final FeedPostRepository feedPostRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final UserRepository userRepository;
    private final CompatibilityScoreService compatibilityScoreService;

    @Transactional(readOnly = true)
    public Page<FeedPostDto> getFeed(Long currentUserId, Pageable pageable) {
        User currentUser = getUser(currentUserId);
        List<FeedPostDto> posts = feedPostRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(post -> toDto(post, currentUser))
                .sorted(Comparator
                        .comparingInt(FeedPostDto::getCompatibilityScore).reversed()
                        .thenComparing(FeedPostDto::getCreatedAt, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        int start = Math.min((int) pageable.getOffset(), posts.size());
        int end = Math.min(start + pageable.getPageSize(), posts.size());
        return new PageImpl<>(posts.subList(start, end), pageable, posts.size());
    }

    @Transactional
    public FeedPostDto createPost(Long currentUserId, CreateFeedPostRequest request) {
        User author = getUser(currentUserId);
        if (request == null || isBlank(request.getImageUrl())) {
            throw new MatchingException("이미지 URL을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }

        FeedPost post = new FeedPost();
        post.setAuthor(author);
        post.setImageUrl(request.getImageUrl().trim());
        post.setCaption(request.getCaption() != null ? request.getCaption().trim() : "");
        post.setInterestTags(cleanTags(request.getInterestTags()));

        return toDto(feedPostRepository.save(post), author);
    }

    @Transactional
    public FeedPostDto likePost(Long currentUserId, String postId) {
        User user = getUser(currentUserId);
        FeedPost post = getPost(postId);
        if (!postLikeRepository.existsByPost_IdAndUser_Id(postId, currentUserId)) {
            PostLike like = new PostLike();
            like.setPost(post);
            like.setUser(user);
            postLikeRepository.save(like);
        }
        return toDto(post, user);
    }

    @Transactional
    public FeedPostDto unlikePost(Long currentUserId, String postId) {
        FeedPost post = getPost(postId);
        postLikeRepository.deleteByPost_IdAndUser_Id(postId, currentUserId);
        return toDto(post, getUser(currentUserId));
    }

    @Transactional(readOnly = true)
    public List<PostCommentDto> getComments(String postId) {
        getPost(postId);
        return postCommentRepository.findByPost_IdOrderByCreatedAtAsc(postId).stream()
                .map(PostCommentDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public PostCommentDto addComment(Long currentUserId, String postId, CreateCommentRequest request) {
        if (request == null || isBlank(request.getContent())) {
            throw new MatchingException("댓글 내용을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }

        PostComment comment = new PostComment();
        comment.setPost(getPost(postId));
        comment.setAuthor(getUser(currentUserId));
        comment.setContent(request.getContent().trim());
        return PostCommentDto.fromEntity(postCommentRepository.save(comment));
    }

    private FeedPostDto toDto(FeedPost post, User currentUser) {
        User author = post.getAuthor();
        int compatibilityScore = author != null && currentUser != null
                ? compatibilityScoreService.calculateScore(currentUser, author, null)
                : 0;
        List<String> sharedInterests = author != null && currentUser != null
                ? compatibilityScoreService.sharedInterests(currentUser, author)
                : List.of();

        if (author != null && currentUser != null && Objects.equals(author.getId(), currentUser.getId())) {
            compatibilityScore = 100;
            sharedInterests = post.getInterestTags() != null ? post.getInterestTags() : List.of();
        }

        return FeedPostDto.fromEntity(
                post,
                postLikeRepository.countByPost_Id(post.getId()),
                postCommentRepository.countByPost_Id(post.getId()),
                currentUser != null && postLikeRepository.existsByPost_IdAndUser_Id(post.getId(), currentUser.getId()),
                compatibilityScore,
                sharedInterests
        );
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MatchingException("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private FeedPost getPost(String postId) {
        return feedPostRepository.findById(postId)
                .orElseThrow(() -> new MatchingException("게시글을 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }

    private List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
