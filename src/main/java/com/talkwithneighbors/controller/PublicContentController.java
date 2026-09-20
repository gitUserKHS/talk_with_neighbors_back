package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.dto.feed.FeedMode;
import com.talkwithneighbors.service.PublicContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicContentController {
    private static final int MAX_PAGE_SIZE = 50;
    // 게스트 피드는 누구에게나 같은 응답이므로 짧게 공유 캐시를 허용한다.
    // Cache-Control을 직접 내려 두면 Spring Security의 no-store 기본 헤더가 덮어쓰지 않는다.
    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final PublicContentService publicContentService;

    @GetMapping("/feed")
    public ResponseEntity<Page<PublicFeedPostDto>> getFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "RECOMMENDED") FeedMode mode,
            @RequestParam(required = false) String region
    ) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(publicContentService.getFeed(mode, region, pageable(page, size)));
    }

    @GetMapping("/meetups")
    public ResponseEntity<Page<PublicMeetupDto>> getMeetups(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String interest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(publicContentService.getMeetups(keyword, interest, pageable(page, size)));
    }

    private Pageable pageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
