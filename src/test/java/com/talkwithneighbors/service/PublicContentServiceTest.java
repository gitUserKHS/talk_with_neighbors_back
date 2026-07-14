package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicContentServiceTest {

    @Mock
    PublicFeedPostRepository feedPostRepository;

    @Mock
    PostCommentRepository postCommentRepository;

    @Mock
    PublicMeetupRepository meetupRepository;

    @Mock
    PostLikeRepository postLikeRepository;

    @Test
    void mapsPublicFeedWithoutViewerSpecificFields() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost post = post("post-1", true);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(post), pageable, 1));
        when(postLikeRepository.countByPost_Id("post-1")).thenReturn(3L);
        when(postCommentRepository.countByPost_Id("post-1")).thenReturn(2L);

        PublicFeedPostDto result = service().getFeed(pageable).getContent().get(0);

        assertThat(result.authorDisplayName()).isEqualTo("이웃");
        assertThat(result.likeCount()).isEqualTo(3);
        assertThat(result.commentCount()).isEqualTo(2);
    }

    @Test
    void failsClosedIfARepositoryRegressionReturnsAPrivatePost() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost privatePost = post("private-post", false);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(privatePost), pageable, 1));

        assertThatThrownBy(() -> service().getFeed(pageable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private feed posts");
    }

    @Test
    void normalizesPublicMeetupFiltersBeforeDatabaseQuery() {
        PageRequest pageable = PageRequest.of(0, 10);
        ChatRoom room = new ChatRoom();
        room.setId("meetup-1");
        room.setName("Book club");
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setInterestTags(new ArrayList<>(List.of("Books")));
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "book", "books", pageable))
                .thenReturn(new PageImpl<>(List.of(room), pageable, 1));

        PublicMeetupDto result = service().getMeetups("  BOOK ", " Books ", pageable)
                .getContent().get(0);

        assertThat(result.id()).isEqualTo("meetup-1");
        verify(meetupRepository)
                .findPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "book", "books", pageable);
    }

    private PublicContentService service() {
        return new PublicContentService(
                feedPostRepository,
                postCommentRepository,
                meetupRepository,
                postLikeRepository
        );
    }

    private FeedPost post(String id, boolean publicPreview) {
        User author = new User();
        author.setId(7L);
        author.setUsername("private-account-handle");
        FeedPost post = new FeedPost();
        post.setId(id);
        post.setAuthor(author);
        post.setImageUrl("https://example.test/image.jpg");
        post.setCaption("Hello neighbors");
        post.setInterestTags(new ArrayList<>(List.of("Books")));
        post.setPublicPreview(publicPreview);
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        return post;
    }
}
