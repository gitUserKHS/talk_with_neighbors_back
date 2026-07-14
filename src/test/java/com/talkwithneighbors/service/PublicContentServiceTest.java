package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.publiccontent.PublicFeedPostDto;
import com.talkwithneighbors.dto.publiccontent.PublicMeetupDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void anonymizesMemberFeedPostsAndUsesRealEngagementCounts() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost post = post("post-1", member(), true);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(post), pageable, 1));
        when(postLikeRepository.countByPost_Id("post-1")).thenReturn(3L);
        when(postCommentRepository.countByPost_Id("post-1")).thenReturn(2L);

        PublicFeedPostDto result = service().getFeed(pageable).getContent().get(0);

        assertThat(result.authorDisplayName()).isEqualTo("이웃");
        assertThat(result.likeCount()).isEqualTo(3);
        assertThat(result.commentCount()).isEqualTo(2);
        assertThat(result.official()).isFalse();
    }

    @Test
    void labelsSystemOwnedFeedAsOfficialWithoutExposingAnAccountId() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost post = post("official-post", systemOwner(), true);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(post), pageable, 1));

        PublicFeedPostDto result = service().getFeed(pageable).getContent().get(0);

        assertThat(result.authorDisplayName()).isEqualTo("이웃톡 운영팀");
        assertThat(result.official()).isTrue();
    }

    @Test
    void failsClosedIfARepositoryRegressionReturnsAPrivatePost() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost privatePost = post("private-post", member(), false);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(privatePost), pageable, 1));

        assertThatThrownBy(() -> service().getFeed(pageable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private feed posts");
    }

    @Test
    void keepsEmptyPublicFeedEmptyWithoutAnInMemoryFallback() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(feedPostRepository.findPublicFeed(pageable)).thenReturn(Page.empty(pageable));

        assertThat(service().getFeed(pageable).getContent()).isEmpty();
        verifyNoInteractions(postLikeRepository, postCommentRepository);
    }

    @Test
    void normalizesMeetupFiltersAndRedactsMemberLocations() {
        PageRequest pageable = PageRequest.of(0, 10);
        ChatRoom room = meetup("member-meetup", member());
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "book", "books", pageable))
                .thenReturn(new PageImpl<>(List.of(room), pageable, 1));

        PublicMeetupDto result = service().getMeetups("  BOOK ", " Books ", pageable)
                .getContent().get(0);

        assertThat(result.official()).isFalse();
        assertThat(result.location()).isNull();
        assertThat(result.locationAddress()).isNull();
        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
        verify(meetupRepository)
                .findPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "book", "books", pageable);
    }

    @Test
    void exposesLocationOnlyForTransparentSystemMeetups() {
        PageRequest pageable = PageRequest.of(0, 10);
        ChatRoom room = meetup("official-meetup", systemOwner());
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "", "", pageable))
                .thenReturn(new PageImpl<>(List.of(room), pageable, 1));

        PublicMeetupDto result = service().getMeetups(null, null, pageable).getContent().get(0);

        assertThat(result.official()).isTrue();
        assertThat(result.location()).isEqualTo("서울도서관 정문 앞");
        assertThat(result.locationAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(result.latitude()).isEqualTo(37.566317);
        assertThat(result.longitude()).isEqualTo(126.977829);
    }

    private PublicContentService service() {
        return new PublicContentService(
                feedPostRepository,
                postCommentRepository,
                meetupRepository,
                postLikeRepository
        );
    }

    private FeedPost post(String id, User author, boolean publicPreview) {
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

    private ChatRoom meetup(String id, User creator) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setCreator(creator);
        room.setName("Book club");
        room.setType(ChatRoomType.GROUP);
        room.setStatus(ChatRoomStatus.ACTIVE);
        room.setPublicRoom(true);
        room.setInterestTags(new ArrayList<>(List.of("Books")));
        room.setLocation("서울도서관 정문 앞");
        room.setLocationAddress("서울특별시 중구 세종대로 110");
        room.setLatitude(37.566317);
        room.setLongitude(126.977829);
        return room;
    }

    private User member() {
        User user = new User();
        user.setId(7L);
        user.setUsername("private-account-handle");
        user.setAccountType(UserAccountType.MEMBER);
        return user;
    }

    private User systemOwner() {
        User user = member();
        user.setUsername("이웃톡 운영팀");
        user.setAccountType(UserAccountType.SYSTEM);
        return user;
    }
}
