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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    void mapsPublicFeedWithoutViewerSpecificFields() {
        PageRequest pageable = PageRequest.of(0, 20);
        FeedPost post = post("post-1", true);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(post), pageable, 1));
        when(postLikeRepository.countByPost_Id("post-1")).thenReturn(3L);
        when(postCommentRepository.countByPost_Id("post-1")).thenReturn(2L);

        PublicFeedPostDto result = service(true).getFeed(pageable).getContent().get(0);

        assertThat(result.authorDisplayName()).isEqualTo("이웃");
        assertThat(result.likeCount()).isEqualTo(3);
        assertThat(result.commentCount()).isEqualTo(2);
        assertThat(result.demo()).isFalse();
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

        PublicMeetupDto result = service(true).getMeetups("  BOOK ", " Books ", pageable)
                .getContent().get(0);

        assertThat(result.id()).isEqualTo("meetup-1");
        assertThat(result.demo()).isFalse();
        verify(meetupRepository)
                .findPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "book", "books", pageable);
    }

    @Test
    void returnsCuratedFeedOnlyWhenEnabledAndTheWholePublicFeedIsEmpty() {
        PageRequest firstPage = PageRequest.of(0, 2);
        PageRequest secondPage = PageRequest.of(1, 2);
        PageRequest pageAfterEnd = PageRequest.of(2, 2);
        when(feedPostRepository.findPublicFeed(firstPage)).thenReturn(Page.empty(firstPage));
        when(feedPostRepository.findPublicFeed(secondPage)).thenReturn(Page.empty(secondPage));
        when(feedPostRepository.findPublicFeed(pageAfterEnd)).thenReturn(Page.empty(pageAfterEnd));

        var first = service(true).getFeed(firstPage);
        var second = service(true).getFeed(secondPage);
        var afterEnd = service(true).getFeed(pageAfterEnd);

        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(PublicFeedPostDto::id)
                .containsExactly("portfolio-demo-feed-01", "portfolio-demo-feed-02");
        assertThat(second.getContent()).extracting(PublicFeedPostDto::id)
                .containsExactly("portfolio-demo-feed-03", "portfolio-demo-feed-04");
        assertThat(afterEnd.getContent()).isEmpty();
        assertThat(first.getContent()).allMatch(PublicFeedPostDto::demo);
        assertThat(first.getContent()).allMatch(item -> item.media().isEmpty() && item.imageUrl() == null);
        verifyNoInteractions(postLikeRepository, postCommentRepository);
    }

    @Test
    void doesNotMixDemoFeedIntoAnEmptyPageWhenRealPublicFeedExists() {
        PageRequest pageable = PageRequest.of(3, 2);
        when(feedPostRepository.findPublicFeed(pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 2));

        assertThat(service(true).getFeed(pageable).getContent()).isEmpty();
        verifyNoInteractions(postLikeRepository, postCommentRepository);
    }

    @Test
    void leavesAnEmptyFeedEmptyWhenPortfolioDemoIsDisabled() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(feedPostRepository.findPublicFeed(pageable)).thenReturn(Page.empty(pageable));

        assertThat(service(false).getFeed(pageable).getContent()).isEmpty();
        verifyNoInteractions(postLikeRepository, postCommentRepository);
    }

    @Test
    void filtersAndPagesCuratedMeetupsWhenTheWholePublicDatasetIsEmpty() {
        PageRequest firstPage = PageRequest.of(0, 1);
        PageRequest secondPage = PageRequest.of(1, 1);
        PageRequest pageAfterEnd = PageRequest.of(2, 1);
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "산책", "산책", firstPage))
                .thenReturn(Page.empty(firstPage));
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "산책", "산책", secondPage))
                .thenReturn(Page.empty(secondPage));
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "산책", "산책", pageAfterEnd))
                .thenReturn(Page.empty(pageAfterEnd));
        when(meetupRepository.countPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE))
                .thenReturn(0L);

        var first = service(true).getMeetups(" 산책 ", " 산책 ", firstPage);
        var second = service(true).getMeetups("산책", "산책", secondPage);
        var afterEnd = service(true).getMeetups("산책", "산책", pageAfterEnd);

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(PublicMeetupDto::id)
                .containsExactly("portfolio-demo-meetup-01");
        assertThat(second.getContent()).extracting(PublicMeetupDto::id)
                .containsExactly("portfolio-demo-meetup-04");
        assertThat(afterEnd.getContent()).isEmpty();
        assertThat(first.getContent().get(0).demo()).isTrue();
        assertThat(first.getContent().get(0).scheduledAt())
                .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2));
        assertThat(first.getContent().get(0).scheduledAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(first.getContent().get(0).scheduledAt().getHour()).isEqualTo(9);
        assertThat(first.getContent().get(0).scheduledAt().getMinute()).isEqualTo(30);
        assertThat(first.getContent().get(0).registrationDeadline().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(first.getContent().get(0).registrationDeadline().getHour()).isEqualTo(11);
        assertThat(first.getContent().get(0).registrationDeadline().getMinute()).isZero();
    }

    @Test
    void keepsAFilteredNoMatchEmptyWhenAnyRealPublicMeetupExists() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "no-match", "", pageable))
                .thenReturn(Page.empty(pageable));
        when(meetupRepository.countPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE))
                .thenReturn(1L);

        assertThat(service(true).getMeetups("no-match", null, pageable).getContent()).isEmpty();
    }

    @Test
    void leavesEmptyMeetupsEmptyWhenPortfolioDemoIsDisabled() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP, ChatRoomStatus.ACTIVE, "", "", pageable))
                .thenReturn(Page.empty(pageable));

        assertThat(service(false).getMeetups(null, null, pageable).getContent()).isEmpty();
    }

    private PublicContentService service() {
        return service(false);
    }

    private PublicContentService service(boolean portfolioDemoEnabled) {
        return new PublicContentService(
                feedPostRepository,
                postCommentRepository,
                meetupRepository,
                postLikeRepository,
                portfolioDemoEnabled
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
