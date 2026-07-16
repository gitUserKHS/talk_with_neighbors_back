package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.publiccontent.PublicFeedPostRepository;
import com.talkwithneighbors.repository.publiccontent.PublicMeetupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestConfig.class)
class PublicContentRepositoryTest {

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    PublicFeedPostRepository feedPostRepository;

    @Autowired
    PublicMeetupRepository meetupRepository;

    @Autowired
    PostLikeRepository postLikeRepository;

    @Autowired
    PostCommentRepository postCommentRepository;

    @Test
    void pagesOnlyExplicitPublicPreviewPostsInTheDatabase() {
        User author = persistUser("neighbor");
        FeedPost legacyPrivate = persistPost("post-private", author, LocalDateTime.of(2026, 1, 3, 9, 0));
        FeedPost publicFirst = persistPost("post-public-1", author, LocalDateTime.of(2026, 1, 1, 9, 0));
        FeedPost publicSecond = persistPost("post-public-2", author, LocalDateTime.of(2026, 1, 2, 9, 0));
        publicFirst.setPublicPreview(true);
        publicSecond.setPublicPreview(true);
        persistComment("private-comment", legacyPrivate, author, LocalDateTime.of(2026, 1, 3, 10, 0));
        entityManager.flush();
        entityManager.clear();

        Page<FeedPost> feedPage = feedPostRepository.findPublicFeed(PageRequest.of(0, 1));

        assertThat(feedPage.getTotalElements()).isEqualTo(2);
        assertThat(feedPage.getContent()).extracting(FeedPost::getId)
                .containsExactly("post-public-2");
        assertThat(entityManager.find(FeedPost.class, "post-private").isPublicPreview()).isFalse();
        assertThat(entityManager.find(PostComment.class, "private-comment")).isNotNull();
    }

    @Test
    void databaseDefaultKeepsRowsPrivateWhenVisibilityIsOmitted() {
        User author = persistUser("legacy-neighbor");
        entityManager.flush();
        LocalDateTime createdAt = LocalDateTime.of(2025, 12, 31, 23, 59);
        entityManager.getEntityManager().createNativeQuery("""
                        INSERT INTO feed_posts
                            (id, author_id, image_url, caption, created_at, updated_at)
                        VALUES
                            (:id, :authorId, :imageUrl, :caption, :createdAt, :updatedAt)
                        """)
                .setParameter("id", "legacy-row")
                .setParameter("authorId", author.getId())
                .setParameter("imageUrl", "https://example.test/legacy.jpg")
                .setParameter("caption", "Created before public previews")
                .setParameter("createdAt", createdAt)
                .setParameter("updatedAt", createdAt)
                .executeUpdate();
        entityManager.clear();

        FeedPost legacy = entityManager.find(FeedPost.class, "legacy-row");

        assertThat(legacy.isPublicPreview()).isFalse();
        assertThat(feedPostRepository.findPublicFeed(PageRequest.of(0, 20)).getContent())
                .extracting(FeedPost::getId)
                .doesNotContain("legacy-row");
    }

    @Test
    void filtersOnlyPublicGroupMeetupsWithDatabasePagination() {
        User creator = persistUser("organizer");
        persistMeetup("meetup-1", creator, true, ChatRoomType.GROUP,
                "Morning book club", List.of("Books"), LocalDateTime.of(2026, 8, 1, 10, 0));
        persistMeetup("meetup-2", creator, true, ChatRoomType.GROUP,
                "Evening readers", List.of("Books", "Coffee"), LocalDateTime.of(2026, 8, 2, 19, 0));
        persistMeetup("meetup-private", creator, false, ChatRoomType.GROUP,
                "Private book club", List.of("Books"), LocalDateTime.of(2026, 8, 3, 10, 0));
        persistMeetup("direct-room", creator, true, ChatRoomType.ONE_ON_ONE,
                "Book chat", List.of("Books"), LocalDateTime.of(2026, 8, 4, 10, 0));
        ChatRoom closed = persistMeetup("meetup-closed", creator, true, ChatRoomType.GROUP,
                "Closed book club", List.of("Books"), LocalDateTime.of(2026, 8, 5, 10, 0));
        closed.setStatus(ChatRoomStatus.CLOSED);
        entityManager.flush();
        entityManager.clear();

        Page<ChatRoom> result = meetupRepository.findPublicMeetups(
                ChatRoomType.GROUP,
                ChatRoomStatus.ACTIVE,
                "book",
                "books",
                PageRequest.of(0, 1)
        );

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(ChatRoom::getId).containsExactly("meetup-1");
        assertThat(meetupRepository.countPublicMeetups(ChatRoomType.GROUP, ChatRoomStatus.ACTIVE))
                .isEqualTo(2);
    }

    @Test
    void batchesEngagementCountsAndCurrentUserLikesForCandidateIds() {
        User author = persistUser("batch-author");
        User reader = persistUser("batch-reader");
        FeedPost first = persistPost("batch-first", author, LocalDateTime.of(2026, 7, 16, 9, 0));
        FeedPost second = persistPost("batch-second", author, LocalDateTime.of(2026, 7, 16, 10, 0));
        persistLike(first, reader);
        persistLike(first, author);
        persistLike(second, reader);
        persistComment("batch-comment-1", first, reader, LocalDateTime.of(2026, 7, 16, 9, 30));
        persistComment("batch-comment-2", first, author, LocalDateTime.of(2026, 7, 16, 9, 40));
        entityManager.flush();
        entityManager.clear();

        assertThat(postLikeRepository.countByPostIds(List.of("batch-first", "batch-second")))
                .extracting(count -> count.postId() + ":" + count.total())
                .containsExactlyInAnyOrder("batch-first:2", "batch-second:1");
        assertThat(postCommentRepository.countByPostIds(List.of("batch-first", "batch-second")))
                .extracting(count -> count.postId() + ":" + count.total())
                .containsExactly("batch-first:2");
        assertThat(postLikeRepository.findLikedPostIds(
                reader.getId(), List.of("batch-first", "batch-second")))
                .containsExactlyInAnyOrder("batch-first", "batch-second");
    }

    private User persistUser(String username) {
        User user = new User();
        user.setEmail(username + "@example.test");
        user.setUsername(username);
        user.setPassword("encoded-password");
        user.setLatitude(37.5);
        user.setLongitude(127.0);
        user.setAddress("Seoul");
        return entityManager.persist(user);
    }

    private FeedPost persistPost(String id, User author, LocalDateTime createdAt) {
        FeedPost post = new FeedPost();
        post.setId(id);
        post.setAuthor(author);
        post.setImageUrl("https://example.test/" + id + ".jpg");
        post.setCaption(id);
        post.setInterestTags(new ArrayList<>(List.of("Books")));
        post.setCreatedAt(createdAt);
        post.setUpdatedAt(createdAt);
        return entityManager.persist(post);
    }

    private void persistComment(
            String id,
            FeedPost post,
            User author,
            LocalDateTime createdAt
    ) {
        PostComment comment = new PostComment();
        comment.setId(id);
        comment.setPost(post);
        comment.setAuthor(author);
        comment.setContent(id);
        comment.setCreatedAt(createdAt);
        entityManager.persist(comment);
    }

    private void persistLike(FeedPost post, User user) {
        PostLike like = new PostLike();
        like.setPost(post);
        like.setUser(user);
        entityManager.persist(like);
    }

    private ChatRoom persistMeetup(
            String id,
            User creator,
            boolean publicRoom,
            ChatRoomType type,
            String title,
            List<String> tags,
            LocalDateTime scheduledAt
    ) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setCreator(creator);
        room.setPublicRoom(publicRoom);
        room.setType(type);
        room.setName(title);
        room.setDescription("A friendly meetup for local readers");
        room.setInterestTags(new ArrayList<>(tags));
        room.setScheduledAt(scheduledAt);
        room.getParticipants().add(creator);
        return entityManager.persist(room);
    }
}
