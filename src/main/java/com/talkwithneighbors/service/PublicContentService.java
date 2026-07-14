package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class PublicContentService {
    private final PublicFeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final PublicMeetupRepository meetupRepository;
    private final PostLikeRepository postLikeRepository;
    private final boolean portfolioDemoEnabled;

    public PublicContentService(
            PublicFeedPostRepository feedPostRepository,
            PostCommentRepository postCommentRepository,
            PublicMeetupRepository meetupRepository,
            PostLikeRepository postLikeRepository,
            @Value("${app.portfolio-demo.enabled:false}") boolean portfolioDemoEnabled
    ) {
        this.feedPostRepository = feedPostRepository;
        this.postCommentRepository = postCommentRepository;
        this.meetupRepository = meetupRepository;
        this.postLikeRepository = postLikeRepository;
        this.portfolioDemoEnabled = portfolioDemoEnabled;
    }

    public Page<PublicFeedPostDto> getFeed(Pageable pageable) {
        Page<FeedPost> page = feedPostRepository.findPublicFeed(pageable);
        if (portfolioDemoEnabled && page.getTotalElements() == 0) {
            return PortfolioDemoContent.feed(pageable);
        }
        return page.map(post -> PublicFeedPostDto.fromEntity(
                        post,
                        postLikeRepository.countByPost_Id(post.getId()),
                        postCommentRepository.countByPost_Id(post.getId())
                ));
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
        if (portfolioDemoEnabled
                && page.getTotalElements() == 0
                && meetupRepository.countPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE) == 0) {
            return PortfolioDemoContent.meetups(normalizedKeyword, normalizedInterest, pageable);
        }
        return page.map(PublicMeetupDto::fromEntity);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
