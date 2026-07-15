package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.feed.CreateCommentRequest;
import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.feed.PostCommentDto;
import com.talkwithneighbors.dto.feed.UpdateCommentRequest;
import com.talkwithneighbors.dto.feed.UpdateFeedPostRequest;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.repository.HiddenContentRepository;
import com.talkwithneighbors.service.media.storage.MediaStoragePath;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.entity.SafetyTargetType;
import com.talkwithneighbors.dto.mypage.MyCommentActivityDto;
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
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedService {
    private final FeedPostRepository feedPostRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final UserRepository userRepository;
    private final CompatibilityScoreService compatibilityScoreService;
    private final UserBlockRepository userBlockRepository;
    private final HiddenContentRepository hiddenContentRepository;
    private final DomainEventPublisher domainEventPublisher;

    @Transactional(readOnly = true)
    public Page<FeedPostDto> getFeed(Long currentUserId, Pageable pageable) {
        User currentUser = getUser(currentUserId);
        List<String> hiddenPostIds = hiddenContentRepository.findTargetIds(currentUserId, SafetyTargetType.FEED_POST);
        Set<Long> excludedUserIds = new HashSet<>(userBlockRepository.findExcludedUserIds(currentUserId));
        List<FeedPostDto> posts = feedPostRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(post -> !hiddenPostIds.contains(post.getId()))
                .filter(post -> post.getAuthor() == null || !excludedUserIds.contains(post.getAuthor().getId()))
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
        String imageUrl = request.getImageUrl().trim();
        if (MediaStoragePath.fromPublicUrl(imageUrl).isPresent()) {
            throw new MatchingException(
                    "서비스에 저장된 미디어는 파일 업로드로만 게시할 수 있어.",
                    HttpStatus.BAD_REQUEST);
        }

        FeedPost post = new FeedPost();
        post.setAuthor(author);
        post.setImageUrl(imageUrl);
        post.setMedia(new java.util.ArrayList<>(List.of(
                new FeedPostMedia(imageUrl, FeedMediaType.IMAGE)
        )));
        post.setCaption(request.getCaption() != null ? request.getCaption().trim() : "");
        post.setInterestTags(cleanTags(request.getInterestTags()));
        post.setPublicPreview(request.isPublicPreview());

        return toDto(feedPostRepository.save(post), author);
    }

    @Transactional
    public FeedPostDto createPost(
            Long currentUserId,
            CreateFeedPostRequest request,
            List<FeedPostMedia> media
    ) {
        if (request == null) {
            throw new MatchingException("게시글 내용을 확인해 주세요.", HttpStatus.BAD_REQUEST);
        }
        if (media == null || media.isEmpty()) {
            throw new MatchingException("사진 또는 동영상을 한 개 이상 선택해 주세요.", HttpStatus.BAD_REQUEST);
        }

        User author = getUser(currentUserId);
        FeedPost post = new FeedPost();
        post.setAuthor(author);
        String previewUrl = media.stream()
                .filter(item -> item.getType() == FeedMediaType.IMAGE)
                .map(FeedPostMedia::getUrl)
                .findFirst()
                .orElse(media.get(0).getUrl());
        post.setImageUrl(previewUrl);
        post.setMedia(new java.util.ArrayList<>(media));
        post.setCaption(request.getCaption() != null ? request.getCaption().trim() : "");
        post.setInterestTags(cleanTags(request.getInterestTags()));
        post.setPublicPreview(request.isPublicPreview());

        return toDto(feedPostRepository.save(post), author);
    }

    @Transactional
    public FeedPostDto likePost(Long currentUserId, String postId) {
        User user = getUser(currentUserId);
        FeedPost post = getPost(postId);
        requireCanInteract(currentUserId, post);
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

    @Transactional
    public FeedPostDto updatePost(Long currentUserId, String postId, UpdateFeedPostRequest request) {
        User currentUser = getUser(currentUserId);
        FeedPost post = getPost(postId);
        requireAuthor(currentUserId, post);
        if (request == null || (request.caption() == null
                && request.interestTags() == null
                && request.publicPreview() == null)) {
            throw new MatchingException("수정할 피드 항목을 하나 이상 입력해 줘.", HttpStatus.BAD_REQUEST);
        }

        if (request.caption() != null) {
            String caption = request.caption().trim();
            if (caption.length() > 2000) {
                throw new MatchingException("캡션은 2,000자 이하로 입력해 줘.", HttpStatus.BAD_REQUEST);
            }
            post.setCaption(caption);
        }
        if (request.interestTags() != null) {
            post.setInterestTags(cleanTags(request.interestTags()));
        }
        if (request.publicPreview() != null) {
            post.setPublicPreview(request.publicPreview());
        }

        return toDto(feedPostRepository.save(post), currentUser);
    }

    @Transactional(readOnly = true)
    public List<PostCommentDto> getComments(Long currentUserId, String postId) {
        FeedPost post = getPost(postId);
        requireCanInteract(currentUserId, post);
        List<String> hiddenCommentIds = hiddenContentRepository.findTargetIds(currentUserId, SafetyTargetType.COMMENT);
        Set<Long> excludedUserIds = new HashSet<>(userBlockRepository.findExcludedUserIds(currentUserId));
        return postCommentRepository.findByPost_IdOrderByCreatedAtAsc(postId).stream()
                .filter(comment -> !hiddenCommentIds.contains(comment.getId()))
                .filter(comment -> comment.getAuthor() == null || !excludedUserIds.contains(comment.getAuthor().getId()))
                .map(PostCommentDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public PostCommentDto addComment(Long currentUserId, String postId, CreateCommentRequest request) {
        if (request == null || isBlank(request.getContent())) {
            throw new MatchingException("댓글 내용을 입력해 주세요.", HttpStatus.BAD_REQUEST);
        }

        FeedPost post = getPost(postId);
        requireCanInteract(currentUserId, post);
        PostComment comment = new PostComment();
        comment.setPost(post);
        comment.setAuthor(getUser(currentUserId));
        comment.setContent(request.getContent().trim());
        return PostCommentDto.fromEntity(postCommentRepository.save(comment));
    }

    @Transactional
    public PostCommentDto updateComment(
            Long currentUserId,
            String commentId,
            UpdateCommentRequest request
    ) {
        getUser(currentUserId);
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new MatchingException("댓글을 찾을 수 없어.", HttpStatus.NOT_FOUND));
        requireCommentAuthor(currentUserId, comment);
        String content = request == null || request.content() == null ? "" : request.content().trim();
        if (content.isEmpty()) {
            throw new MatchingException("댓글 내용을 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        if (content.length() > 1000) {
            throw new MatchingException("댓글은 1,000자 이하로 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        comment.setContent(content);
        return PostCommentDto.fromEntity(postCommentRepository.save(comment));
    }

    @Transactional(readOnly = true)
    public List<FeedPostDto> myPosts(Long currentUserId) {
        User user = getUser(currentUserId);
        return feedPostRepository.findByAuthor_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(post -> toDto(post, user))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedPostDto> likedPosts(Long currentUserId) {
        User user = getUser(currentUserId);
        return postLikeRepository.findByUser_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(PostLike::getPost)
                .filter(Objects::nonNull)
                .map(post -> toDto(post, user))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyCommentActivityDto> myComments(Long currentUserId) {
        return postCommentRepository.findByAuthor_IdOrderByCreatedAtDesc(currentUserId).stream()
                .map(comment -> new MyCommentActivityDto(comment.getId(), comment.getPost().getId(),
                        comment.getPost().getCaption(), comment.getContent(), comment.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void deletePost(Long currentUserId, String postId) {
        FeedPost post = getPost(postId);
        requireAuthor(currentUserId, post);
        postLikeRepository.deleteByPost_Id(postId);
        postCommentRepository.deleteByPost_Id(postId);
        List<String> mediaUrls = post.getMedia() == null
                ? List.of()
                : post.getMedia().stream()
                        .filter(this::isServerOwnedFeedUpload)
                        .flatMap(media -> java.util.stream.Stream.of(media.getUrl(), media.getThumbnailUrl()))
                        .filter(Objects::nonNull)
                        .filter(url -> !url.isBlank())
                        .filter(this::isFeedStorageUrl)
                        .distinct()
                        .toList();
        feedPostRepository.delete(post);
        if (!mediaUrls.isEmpty()) {
            domainEventPublisher.publish(MediaFilesDeletedEvent.create(
                    "FeedPost", postId, mediaUrls));
        }
    }

    @Transactional
    public void deleteComment(Long currentUserId, String commentId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new MatchingException("댓글을 찾을 수 없어요.", HttpStatus.NOT_FOUND));
        requireCommentAuthor(currentUserId, comment);
        postCommentRepository.delete(comment);
    }

    private void requireAuthor(Long currentUserId, FeedPost post) {
        if (post.getAuthor() == null || !Objects.equals(post.getAuthor().getId(), currentUserId)) {
            throw new MatchingException("작성자만 이 게시글을 수정하거나 삭제할 수 있어.", HttpStatus.FORBIDDEN);
        }
    }

    private void requireCommentAuthor(Long currentUserId, PostComment comment) {
        if (comment.getAuthor() == null || !Objects.equals(comment.getAuthor().getId(), currentUserId)) {
            throw new MatchingException("작성자만 이 댓글을 수정하거나 삭제할 수 있어.", HttpStatus.FORBIDDEN);
        }
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

    private void requireCanInteract(Long currentUserId, FeedPost post) {
        if (post.getAuthor() != null && userBlockRepository.existsBetween(currentUserId, post.getAuthor().getId())) {
            throw new MatchingException("차단 관계인 사용자와는 상호작용할 수 없어요.", HttpStatus.FORBIDDEN);
        }
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

    /**
     * Only multipart uploads carry server-produced object metadata. JSON posts
     * deliberately do not, so a client-controlled URL can never become an S3
     * deletion capability.
     */
    private boolean isServerOwnedFeedUpload(FeedPostMedia media) {
        return media != null
                && media.getContentType() != null
                && !media.getContentType().isBlank()
                && media.getSizeBytes() != null
                && isFeedStorageUrl(media.getUrl());
    }

    private boolean isFeedStorageUrl(String url) {
        return MediaStoragePath.fromPublicUrl(url)
                .map(relativeKey -> relativeKey.startsWith("feed/"))
                .orElse(false);
    }
}
