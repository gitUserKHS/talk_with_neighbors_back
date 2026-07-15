package com.talkwithneighbors.service;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.PostComment;
import com.talkwithneighbors.entity.PostLike;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.FeedPostRepository;
import com.talkwithneighbors.repository.PostCommentRepository;
import com.talkwithneighbors.repository.PostLikeRepository;
import com.talkwithneighbors.repository.MessageRepository;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    @Autowired
    ChatScheduleRepository chatScheduleRepository;

    @Autowired
    MessageRepository messageRepository;

    @Test
    void repeatedSyncIsIdempotentAndPreservesMemberEngagement() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        OfficialContentSeedService service = service(clock);

        service.sync();
        User owner = userRepository.findByEmail(OfficialContentSeedService.OFFICIAL_EMAIL).orElseThrow();
        ChatRoom joinedMeetup = activeOfficialMeetups(owner).get(0);
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

        ChatSchedule driftedSchedule = chatScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getRoom().getId().equals(joinedMeetup.getId()))
                .findFirst()
                .orElseThrow();
        int seedDuration = driftedSchedule.getDurationMinutes();
        Instant adjustedStart = driftedSchedule.getStartsAt().plusSeconds(3_600);
        driftedSchedule.setStartsAt(adjustedStart);
        driftedSchedule.setDurationMinutes(111);
        driftedSchedule.setTitle("stale title");
        driftedSchedule.setDescription("stale description");
        driftedSchedule.setTimeZone("UTC");
        driftedSchedule.setLocation("stale location");
        driftedSchedule.setLocationAddress("stale address");
        driftedSchedule.setLatitude(0.0);
        driftedSchedule.setLongitude(0.0);
        driftedSchedule.setKakaoPlaceId("stale-place");
        chatScheduleRepository.saveAndFlush(driftedSchedule);
        Message driftedCard = messageRepository.findBySchedule_IdAndChatRoom_Id(
                driftedSchedule.getId(), joinedMeetup.getId()).orElseThrow();
        driftedCard.setContent("stale card");
        messageRepository.saveAndFlush(driftedCard);

        service.sync();

        assertThat(userRepository.findAll()).filteredOn(user -> user.getAccountType() == UserAccountType.SYSTEM)
                .hasSize(1);
        assertThat(feedPostRepository.count()).isEqualTo(3);
        assertThat(postCommentRepository.count()).isEqualTo(4);
        assertThat(postLikeRepository.count()).isEqualTo(1);
        assertThat(chatRoomRepository.count()).isEqualTo(2);
        assertThat(chatScheduleRepository.count()).isEqualTo(2);
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(chatScheduleRepository.findAll()).allSatisfy(schedule -> {
            assertThat(schedule.getTimeZone()).isEqualTo("Asia/Seoul");
            assertThat(schedule.getRsvps()).singleElement().satisfies(rsvp -> {
                assertThat(rsvp.getUser().getId()).isEqualTo(owner.getId());
                assertThat(rsvp.getStatus()).isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
            });
        });
        assertThat(chatRoomRepository.findById(joinedMeetup.getId()).orElseThrow().getParticipants())
                .extracting(User::getId)
                .contains(owner.getId(), member.getId());
        ChatRoom refreshedMeetup = chatRoomRepository.findById(joinedMeetup.getId()).orElseThrow();
        assertThat(refreshedMeetup.getScheduledAt())
                .isEqualTo(LocalDateTime.ofInstant(adjustedStart, ZoneOffset.UTC));
        ChatSchedule refreshedSchedule = chatScheduleRepository.findById(
                driftedSchedule.getId()).orElseThrow();
        assertThat(refreshedSchedule.getStartsAt()).isEqualTo(adjustedStart);
        assertThat(refreshedSchedule.getDurationMinutes()).isEqualTo(seedDuration);
        assertThat(refreshedMeetup.getDurationMinutes()).isEqualTo(seedDuration);
        assertThat(refreshedSchedule.getTitle()).isEqualTo(refreshedMeetup.getName());
        assertThat(refreshedSchedule.getDescription()).isEqualTo(refreshedMeetup.getDescription());
        assertThat(refreshedSchedule.getTimeZone()).isEqualTo("Asia/Seoul");
        assertThat(refreshedSchedule.getLocation()).isEqualTo(refreshedMeetup.getLocation());
        assertThat(refreshedSchedule.getLocationAddress())
                .isEqualTo(refreshedMeetup.getLocationAddress());
        assertThat(refreshedSchedule.getLatitude()).isEqualTo(refreshedMeetup.getLatitude());
        assertThat(refreshedSchedule.getLongitude()).isEqualTo(refreshedMeetup.getLongitude());
        assertThat(refreshedSchedule.getKakaoPlaceId()).isEqualTo(refreshedMeetup.getKakaoPlaceId());
        assertThat(messageRepository.findBySchedule_IdAndChatRoom_Id(
                refreshedSchedule.getId(), refreshedMeetup.getId()).orElseThrow().getContent())
                .isEqualTo("일정: " + refreshedSchedule.getTitle());
        assertThat(owner.getProfileDiscoverable()).isFalse();
        assertThat(owner.getPasswordLoginEnabled()).isFalse();
        assertThat(owner.isProfileComplete()).isFalse();
        assertThat(feedPostRepository.findById(OfficialContentSeedService.WALK_POST_ID).orElseThrow()
                .getImageUrl()).isEqualTo("/uploads/feed/official-evening-walk.svg");
    }

    @Test
    void syncAdoptsLegacyScheduleBackfilledForExistingOfficialRoom() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        OfficialContentSeedService service = service(clock);
        service.sync();

        User owner = userRepository.findByEmail(
                OfficialContentSeedService.OFFICIAL_EMAIL).orElseThrow();
        ChatRoom room = activeOfficialMeetups(owner).get(0);
        ChatSchedule original = chatScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getRoom().getId().equals(room.getId()))
                .findFirst()
                .orElseThrow();
        Message originalCard = messageRepository.findBySchedule_IdAndChatRoom_Id(
                original.getId(), room.getId()).orElseThrow();
        Instant preservedStart = original.getStartsAt();
        int preservedDuration = original.getDurationMinutes();
        messageRepository.delete(originalCard);
        messageRepository.flush();
        chatScheduleRepository.delete(original);
        chatScheduleRepository.flush();

        String legacyScheduleId = deterministicId("legacy-chat-schedule:", room.getId());
        ChatSchedule migrated = new ChatSchedule();
        migrated.setId(legacyScheduleId);
        migrated.setRoom(room);
        migrated.setCreator(owner);
        migrated.setTitle("legacy migration title");
        migrated.setStartsAt(preservedStart);
        migrated.setDurationMinutes(preservedDuration);
        migrated.setTimeZone("Asia/Seoul");
        migrated.setStatus(ChatScheduleStatus.SCHEDULED);
        chatScheduleRepository.saveAndFlush(migrated);
        Message migratedCard = new Message();
        migratedCard.setId(deterministicId(
                "legacy-chat-schedule-message:", legacyScheduleId));
        migratedCard.setChatRoom(room);
        migratedCard.setSender(owner);
        migratedCard.setContent("legacy migration card");
        migratedCard.setType(Message.MessageType.SCHEDULE);
        migratedCard.setSchedule(migrated);
        messageRepository.saveAndFlush(migratedCard);

        service.sync();

        List<ChatSchedule> roomSchedules = chatScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getRoom().getId().equals(room.getId()))
                .toList();
        assertThat(roomSchedules).singleElement().satisfies(schedule -> {
            assertThat(schedule.getId()).isEqualTo(legacyScheduleId);
            assertThat(schedule.getTitle()).isEqualTo(room.getName());
            assertThat(schedule.getStartsAt()).isEqualTo(preservedStart);
        });
        assertThat(messageRepository.findAll()).filteredOn(message ->
                message.getChatRoom().getId().equals(room.getId()))
                .singleElement()
                .satisfies(card -> {
                    assertThat(card.getSchedule().getId()).isEqualTo(legacyScheduleId);
                    assertThat(card.getContent()).isEqualTo("일정: " + room.getName());
                });
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

    @Test
    void laterSyncClosesPastOfficialOccurrenceEvenAfterProjectionWasCleared() {
        OfficialContentSeedService first = service(
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        first.sync();
        User owner = userRepository.findByEmail(
                OfficialContentSeedService.OFFICIAL_EMAIL).orElseThrow();
        ChatRoom pastOccurrence = activeOfficialMeetups(owner).get(0);
        ChatSchedule canonical = chatScheduleRepository.findAll().stream()
                .filter(schedule -> schedule.getRoom().getId().equals(pastOccurrence.getId()))
                .findFirst()
                .orElseThrow();
        String historicalScheduleId = canonical.getId();
        Instant historicalStart = canonical.getStartsAt();
        String historicalCardId = messageRepository
                .findBySchedule_IdAndChatRoom_Id(canonical.getId(), pastOccurrence.getId())
                .orElseThrow()
                .getId();
        assertThat(canonical.getStartsAt())
                .isBefore(Instant.parse("2026-07-21T00:00:00Z"));
        pastOccurrence.setScheduledAt(null);
        pastOccurrence.setMeetupTimeBasis(null);
        pastOccurrence.setDurationMinutes(null);
        chatRoomRepository.saveAndFlush(pastOccurrence);

        Instant laterInstant = Instant.parse("2026-07-21T00:00:00Z");
        OfficialContentSeedService later = service(Clock.fixed(laterInstant, ZoneOffset.UTC));
        later.sync();

        assertThat(chatRoomRepository.findById(pastOccurrence.getId()).orElseThrow().getStatus())
                .isEqualTo(ChatRoomStatus.CLOSED);
        assertThat(chatScheduleRepository.findById(historicalScheduleId).orElseThrow())
                .satisfies(historical -> {
                    assertThat(historical.getRoom().getId()).isEqualTo(pastOccurrence.getId());
                    assertThat(historical.getStartsAt()).isEqualTo(historicalStart);
                    assertThat(historical.getStatus()).isEqualTo(ChatScheduleStatus.SCHEDULED);
                });
        assertThat(messageRepository.findById(historicalCardId)).isPresent();

        List<ChatRoom> currentOccurrences = activeOfficialMeetups(owner);
        assertThat(currentOccurrences).hasSize(2).allSatisfy(room -> {
            assertThat(room.getScheduledAt()).isNotNull();
            assertThat(room.getScheduledAt())
                    .isAfter(LocalDateTime.ofInstant(laterInstant, ZoneOffset.UTC));
            assertThat(room.getDurationMinutes()).isPositive();
        });
        Set<String> currentRoomIds = currentOccurrences.stream()
                .map(ChatRoom::getId)
                .collect(Collectors.toSet());
        List<ChatSchedule> currentSchedules = chatScheduleRepository.findAll().stream()
                .filter(schedule -> currentRoomIds.contains(schedule.getRoom().getId()))
                .toList();
        assertThat(currentSchedules).hasSize(2).allSatisfy(schedule -> {
            assertThat(schedule.getStatus()).isEqualTo(ChatScheduleStatus.SCHEDULED);
            assertThat(schedule.getStartsAt()).isAfter(laterInstant);
            assertThat(schedule.getRsvps()).singleElement().satisfies(rsvp -> {
                assertThat(rsvp.getUser().getId()).isEqualTo(owner.getId());
                assertThat(rsvp.getStatus()).isEqualTo(ChatScheduleRsvpStatus.ATTENDING);
            });
            assertThat(messageRepository.findBySchedule_IdAndChatRoom_Id(
                    schedule.getId(), schedule.getRoom().getId()))
                    .get()
                    .satisfies(card -> {
                        assertThat(card.getType()).isEqualTo(Message.MessageType.SCHEDULE);
                        assertThat(card.getSender().getId()).isEqualTo(owner.getId());
                    });
        });

        Set<String> scheduleIdsAfterFirstLaterSync = chatScheduleRepository.findAll().stream()
                .map(ChatSchedule::getId)
                .collect(Collectors.toSet());
        Set<String> cardIdsAfterFirstLaterSync = messageRepository.findAll().stream()
                .filter(message -> message.getType() == Message.MessageType.SCHEDULE)
                .map(Message::getId)
                .collect(Collectors.toSet());

        later.sync();

        assertThat(activeOfficialMeetups(owner))
                .extracting(ChatRoom::getId)
                .containsExactlyInAnyOrderElementsOf(currentRoomIds);
        assertThat(chatScheduleRepository.findAll())
                .extracting(ChatSchedule::getId)
                .containsExactlyInAnyOrderElementsOf(scheduleIdsAfterFirstLaterSync);
        assertThat(messageRepository.findAll().stream()
                .filter(message -> message.getType() == Message.MessageType.SCHEDULE)
                .map(Message::getId)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(cardIdsAfterFirstLaterSync);
    }

    private OfficialContentSeedService service(Clock clock) {
        return new OfficialContentSeedService(
                userRepository,
                feedPostRepository,
                postCommentRepository,
                chatRoomRepository,
                chatScheduleRepository,
                messageRepository,
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

    private String deterministicId(String namespace, String value) {
        return UUID.nameUUIDFromBytes(
                (namespace + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
