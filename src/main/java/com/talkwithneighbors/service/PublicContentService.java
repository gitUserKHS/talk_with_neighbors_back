package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicContentService {
    private final PublicFeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final PublicMeetupRepository meetupRepository;
    private final PostLikeRepository postLikeRepository;

    public Page<PublicFeedPostDto> getFeed(Pageable pageable) {
        return feedPostRepository.findPublicFeed(pageable)
                .map(post -> PublicFeedPostDto.fromEntity(
                        post,
                        postLikeRepository.countByPost_Id(post.getId()),
                        postCommentRepository.countByPost_Id(post.getId())
                ));
    }

    public Page<PublicMeetupDto> getMeetups(String keyword, String interest, Pageable pageable) {
        return meetupRepository.findPublicMeetups(
                        ChatRoomType.GROUP,
                        ChatRoomStatus.ACTIVE,
                        normalize(keyword),
                        normalize(interest),
                        pageable
                )
                .map(PublicMeetupDto::fromEntity);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
