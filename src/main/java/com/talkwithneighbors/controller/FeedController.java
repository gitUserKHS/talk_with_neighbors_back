package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.feed.CreateCommentRequest;
import com.talkwithneighbors.dto.feed.CreateFeedPostRequest;
import com.talkwithneighbors.dto.feed.FeedPostDto;
import com.talkwithneighbors.dto.feed.PostCommentDto;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.FeedService;
import com.talkwithneighbors.service.MediaStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
@RequireLogin
public class FeedController {
    private final FeedService feedService;
    private final MediaStorageService mediaStorageService;

    @GetMapping
    public ResponseEntity<Page<FeedPostDto>> getFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            UserSession userSession
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        return ResponseEntity.ok(feedService.getFeed(userSession.getUserId(), pageable));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FeedPostDto> createPost(
            @RequestBody CreateFeedPostRequest request,
            UserSession userSession
    ) {
        return ResponseEntity.ok(feedService.createPost(userSession.getUserId(), request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FeedPostDto> createPostWithMedia(
            @RequestPart("post") CreateFeedPostRequest request,
            @RequestPart("files") List<MultipartFile> files,
            UserSession userSession
    ) {
        List<FeedPostMedia> storedMedia = mediaStorageService.storePostMedia(files);
        try {
            return ResponseEntity.ok(feedService.createPost(userSession.getUserId(), request, storedMedia));
        } catch (RuntimeException exception) {
            mediaStorageService.deletePostMedia(storedMedia.stream().map(FeedPostMedia::getUrl).toList());
            throw exception;
        }
    }

    @PostMapping("/{postId}/likes")
    public ResponseEntity<FeedPostDto> likePost(
            @PathVariable String postId,
            UserSession userSession
    ) {
        return ResponseEntity.ok(feedService.likePost(userSession.getUserId(), postId));
    }

    @DeleteMapping("/{postId}/likes")
    public ResponseEntity<FeedPostDto> unlikePost(
            @PathVariable String postId,
            UserSession userSession
    ) {
        return ResponseEntity.ok(feedService.unlikePost(userSession.getUserId(), postId));
    }

    @GetMapping("/{postId}/comments")
    public ResponseEntity<List<PostCommentDto>> getComments(@PathVariable String postId, UserSession userSession) {
        return ResponseEntity.ok(feedService.getComments(userSession.getUserId(), postId));
    }

    @PostMapping("/{postId}/comments")
    public ResponseEntity<PostCommentDto> addComment(
            @PathVariable String postId,
            @RequestBody CreateCommentRequest request,
            UserSession userSession
    ) {
        return ResponseEntity.ok(feedService.addComment(userSession.getUserId(), postId, request));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable String postId, UserSession userSession) {
        feedService.deletePost(userSession.getUserId(), postId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable String commentId, UserSession userSession) {
        feedService.deleteComment(userSession.getUserId(), commentId);
        return ResponseEntity.noContent().build();
    }
}
