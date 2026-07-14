package com.talkwithneighbors.service;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestConfig.class)
class OfficialContentSeedServiceTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    FeedPostRepository feedPostRepository;

    @Autowired
    PostCommentRepository postCommentRepository;

    @Autowired
    PostLikeRepository postLikeRepository;

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Test
    void repeatedSyncIsIdempotentAndPreservesMemberEngagement() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        OfficialContentSeedService service = service(clock);

        service.sync();
        User owner = userRepository.findByEmail(OfficialContentSeedService.OFFICIAL_EMAIL).orElseThrow();
        ChatRoom joinedMeetup = activeOfficialMeetups(owner).get(0);
        LocalDateTime originalSchedule = joinedMeetup.getScheduledAt();
        assertThat(userRepository.findAll()).filteredOn(user -> user.getAccountType() == UserAccountType.SYSTEM)
                .containsExactly(owner);
        assertThat(postLikeRepository.count()).isZero();
        assertThat(activeOfficialMeetups(owner))
                .allSatisfy(room -> assertThat(room.getParticipants()).containsExactly(owner));

        User member = persistMember();
        PostLike like = new PostLike();
        like.setPost(feedPostRepository.findById(OfficialContentSeedService.WALK_POST_ID).orElseThrow());
        like.setUser(member);
        postLikeRepository.save(like);

        PostComment memberComment = new PostComment();
        memberComment.setId("44444444-4444-4444-8444-444444444401");
        memberComment.setPost(feedPostRepository.findById(OfficialContentSeedService.WALK_POST_ID).orElseThrow());
        memberComment.setAuthor(member);
        memberComment.setContent("저도 다음 산책에 참여하고 싶어요.");
        postCommentRepository.save(memberComment);

        joinedMeetup.getParticipants().add(member);
        chatRoomRepository.save(joinedMeetup);

        service.sync();

        assertThat(userRepository.findAll()).filteredOn(user -> user.getAccountType() == UserAccountType.SYSTEM)
                .hasSize(1);
        assertThat(feedPostRepository.count()).isEqualTo(3);
        assertThat(postCommentRepository.count()).isEqualTo(4);
        assertThat(postLikeRepository.count()).isEqualTo(1);
        assertThat(chatRoomRepository.count()).isEqualTo(2);
        assertThat(chatRoomRepository.findById(joinedMeetup.getId()).orElseThrow().getParticipants())
                .extracting(User::getId)
                .contains(owner.getId(), member.getId());
        assertThat(chatRoomRepository.findById(joinedMeetup.getId()).orElseThrow().getScheduledAt())
                .isEqualTo(originalSchedule);
        assertThat(owner.getProfileDiscoverable()).isFalse();
        assertThat(owner.getPasswordLoginEnabled()).isFalse();
        assertThat(owner.isProfileComplete()).isFalse();
        assertThat(feedPostRepository.findById(OfficialContentSeedService.WALK_POST_ID).orElseThrow()
                .getImageUrl()).isEqualTo("/uploads/feed/official-evening-walk.svg");
    }

    @Test
    void aLaterSyncClosesPastOccurrencesAndCreatesTwoNewOnesWithoutMovingHistory() {
        OfficialContentSeedService first = service(
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        first.sync();
        User owner = userRepository.findByEmail(OfficialContentSeedService.OFFICIAL_EMAIL).orElseThrow();
        List<ChatRoom> original = activeOfficialMeetups(owner);
        Map<String, LocalDateTime> originalSchedules = original.stream()
                .collect(Collectors.toMap(ChatRoom::getId, ChatRoom::getScheduledAt));

        OfficialContentSeedService nextWeek = service(
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));
        nextWeek.sync();

        List<ChatRoom> all = chatRoomRepository.findByCreator_IdAndTypeOrderByScheduledAtDesc(
                owner.getId(), ChatRoomType.GROUP);
        assertThat(all).hasSize(4);
        assertThat(all).filteredOn(room -> room.getStatus() == ChatRoomStatus.ACTIVE).hasSize(2);
        assertThat(all).filteredOn(room -> room.getStatus() == ChatRoomStatus.CLOSED).hasSize(2);
        assertThat(all).filteredOn(room -> originalSchedules.containsKey(room.getId()))
                .allSatisfy(room -> assertThat(room.getScheduledAt())
                        .isEqualTo(originalSchedules.get(room.getId())));
        assertThat(all).filteredOn(room -> room.getStatus() == ChatRoomStatus.ACTIVE)
                .allSatisfy(room -> assertThat(room.getScheduledAt())
                        .isAfter(LocalDateTime.ofInstant(nextWeekInstant(), ZoneOffset.UTC)));
    }

    private OfficialContentSeedService service(Clock clock) {
        return new OfficialContentSeedService(
                userRepository,
                feedPostRepository,
                postCommentRepository,
                chatRoomRepository,
                new BCryptPasswordEncoder(4),
                clock
        );
    }

    private List<ChatRoom> activeOfficialMeetups(User owner) {
        return chatRoomRepository.findByCreator_IdAndTypeOrderByScheduledAtDesc(owner.getId(), ChatRoomType.GROUP)
                .stream()
                .filter(room -> room.getStatus() == ChatRoomStatus.ACTIVE)
                .toList();
    }

    private User persistMember() {
        User user = new User();
        user.setEmail("member@example.test");
        user.setUsername("member");
        user.setPassword("encoded-password");
        user.setAccountType(UserAccountType.MEMBER);
        user.setLatitude(37.5);
        user.setLongitude(127.0);
        user.setAddress("서울특별시");
        return userRepository.saveAndFlush(user);
    }

    private Instant nextWeekInstant() {
        return Instant.parse("2026-07-20T12:00:00Z");
    }
}
