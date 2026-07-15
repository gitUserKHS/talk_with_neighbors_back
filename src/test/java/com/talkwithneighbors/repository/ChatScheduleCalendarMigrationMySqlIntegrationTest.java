package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Tag("mysql")
@ActiveProfiles("mysql-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatScheduleCalendarMigrationMySqlIntegrationTest {
    private static final Path MIGRATION = Path.of(
            "deploy",
            "k8s",
            "database-migrations",
            "V2026071601__backfill_chat_schedule_calendar.sql"
    );

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatScheduleRepository chatScheduleRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void backfillRepairsDependentsForPreexistingDeterministicScheduleAndIsRetrySafe() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User host = userRepository.saveAndFlush(user("calendar-migration-" + suffix));

        Instant cleanStart = Instant.parse("2099-08-01T10:00:00Z");
        ChatRoom cleanRoom = chatRoomRepository.saveAndFlush(
                legacyRoom("clean-" + suffix, host, cleanStart));

        Instant partialStart = Instant.parse("2099-08-02T10:00:00Z");
        ChatRoom partialRoom = chatRoomRepository.saveAndFlush(
                legacyRoom("partial-" + suffix, host, partialStart));
        String partialScheduleId = deterministicId(
                "legacy-chat-schedule:", partialRoom.getId());
        ChatSchedule stalePartialSchedule = schedule(
                partialScheduleId, partialRoom, host, partialStart.minusSeconds(86_400));
        stalePartialSchedule.setTitle("stale partial title");
        stalePartialSchedule.setDurationMinutes(30);
        chatScheduleRepository.saveAndFlush(stalePartialSchedule);

        Instant representedStart = Instant.parse("2099-08-03T10:00:00Z");
        ChatRoom representedRoom = chatRoomRepository.saveAndFlush(
                legacyRoom("represented-" + suffix, host, representedStart));
        String representedScheduleId = UUID.randomUUID().toString();
        ChatSchedule representedSchedule = chatScheduleRepository.saveAndFlush(schedule(
                representedScheduleId, representedRoom, host, representedStart));
        Message existingCard = new Message();
        existingCard.setId(UUID.randomUUID().toString());
        existingCard.setChatRoom(representedRoom);
        existingCard.setSender(host);
        existingCard.setContent("Existing schedule card");
        existingCard.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        existingCard.setType(Message.MessageType.SCHEDULE);
        existingCard.setSchedule(representedSchedule);
        messageRepository.saveAndFlush(existingCard);

        Instant privateStart = Instant.parse("2099-08-04T10:00:00Z");
        ChatRoom privateRoomCandidate = legacyRoom(
                "private-" + suffix, host, privateStart);
        privateRoomCandidate.setPublicRoom(false);
        ChatRoom privateRoom = chatRoomRepository.saveAndFlush(privateRoomCandidate);
        String privateScheduleId = UUID.randomUUID().toString();
        chatScheduleRepository.saveAndFlush(schedule(
                privateScheduleId, privateRoom, host, privateStart));

        Instant cancelledStart = Instant.parse("2099-08-05T10:00:00Z");
        ChatRoom cancelledRoom = chatRoomRepository.saveAndFlush(
                legacyRoom("cancelled-" + suffix, host, cancelledStart));
        ChatSchedule cancelledSchedule = schedule(
                UUID.randomUUID().toString(), cancelledRoom, host, cancelledStart);
        cancelledSchedule.setStatus(ChatScheduleStatus.CANCELLED);
        cancelledSchedule.setCancelledAt(Instant.parse("2099-08-01T00:00:00Z"));
        chatScheduleRepository.saveAndFlush(cancelledSchedule);

        applyMigration();

        String cleanScheduleId = deterministicId("legacy-chat-schedule:", cleanRoom.getId());
        assertCompleteBackfill(cleanRoom.getId(), cleanScheduleId,
                deterministicId("legacy-chat-schedule-message:", cleanScheduleId), host.getId());
        assertCompleteBackfill(partialRoom.getId(), partialScheduleId,
                deterministicId("legacy-chat-schedule-message:", partialScheduleId), host.getId());
        assertThat(chatScheduleRepository.findById(partialScheduleId)).get().satisfies(schedule -> {
            assertThat(schedule.getStartsAt()).isEqualTo(partialStart.minusSeconds(86_400));
            assertThat(schedule.getTitle()).isEqualTo("stale partial title");
            assertThat(schedule.getDurationMinutes()).isEqualTo(30);
        });

        assertThat(scheduleCount(representedRoom.getId())).isEqualTo(1);
        assertThat(chatScheduleRepository.existsById(deterministicId(
                "legacy-chat-schedule:", representedRoom.getId()))).isFalse();
        assertThat(rsvpCount(representedScheduleId, host.getId())).isEqualTo(1);
        assertThat(cardCount(representedScheduleId)).isEqualTo(1);
        assertThat(readCount(existingCard.getId(), host.getId())).isEqualTo(1);
        assertThat(scheduleCount(privateRoom.getId())).isEqualTo(1);
        assertThat(rsvpCount(privateScheduleId, host.getId())).isZero();
        assertThat(cardCount(privateScheduleId)).isZero();
        String cancelledRoomBackfillId = deterministicId(
                "legacy-chat-schedule:", cancelledRoom.getId());
        assertThat(scheduleCount(cancelledRoom.getId())).isEqualTo(2);
        assertThat(activeScheduleCount(cancelledRoom.getId())).isEqualTo(1);
        assertCompleteBackfill(cancelledRoom.getId(), cancelledRoomBackfillId,
                deterministicId("legacy-chat-schedule-message:", cancelledRoomBackfillId),
                host.getId(), 2);

        applyMigration();

        assertThat(scheduleCount(cleanRoom.getId())).isEqualTo(1);
        assertThat(scheduleCount(partialRoom.getId())).isEqualTo(1);
        assertThat(scheduleCount(representedRoom.getId())).isEqualTo(1);
        assertThat(rsvpCount(cleanScheduleId, host.getId())).isEqualTo(1);
        assertThat(rsvpCount(partialScheduleId, host.getId())).isEqualTo(1);
        assertThat(rsvpCount(representedScheduleId, host.getId())).isEqualTo(1);
        assertThat(cardCount(cleanScheduleId)).isEqualTo(1);
        assertThat(cardCount(partialScheduleId)).isEqualTo(1);
        assertThat(cardCount(representedScheduleId)).isEqualTo(1);
        assertThat(readCount(existingCard.getId(), host.getId())).isEqualTo(1);
        assertThat(scheduleCount(privateRoom.getId())).isEqualTo(1);
        assertThat(rsvpCount(privateScheduleId, host.getId())).isZero();
        assertThat(cardCount(privateScheduleId)).isZero();
        assertThat(scheduleCount(cancelledRoom.getId())).isEqualTo(2);
        assertThat(activeScheduleCount(cancelledRoom.getId())).isEqualTo(1);
    }

    private void assertCompleteBackfill(
            String roomId,
            String scheduleId,
            String messageId,
            Long hostId
    ) {
        assertCompleteBackfill(roomId, scheduleId, messageId, hostId, 1);
    }

    private void assertCompleteBackfill(
            String roomId,
            String scheduleId,
            String messageId,
            Long hostId,
            int expectedRoomScheduleCount
    ) {
        assertThat(scheduleCount(roomId)).isEqualTo(expectedRoomScheduleCount);
        assertThat(chatScheduleRepository.existsById(scheduleId)).isTrue();
        assertThat(rsvpCount(scheduleId, hostId)).isEqualTo(1);
        assertThat(cardCount(scheduleId)).isEqualTo(1);
        assertThat(messageRepository.findById(messageId)).isPresent();
        assertThat(readCount(messageId, hostId)).isEqualTo(1);
    }

    private int scheduleCount(String roomId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_schedules WHERE room_id = ?",
                Integer.class,
                roomId);
    }

    private int rsvpCount(String scheduleId, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_schedule_rsvps WHERE schedule_id = ? AND user_id = ?",
                Integer.class,
                scheduleId,
                userId);
    }

    private int activeScheduleCount(String roomId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_schedules WHERE room_id = ? AND status = 'SCHEDULED'",
                Integer.class,
                roomId);
    }

    private int cardCount(String scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE schedule_id = ?",
                Integer.class,
                scheduleId);
    }

    private int readCount(String messageId, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_read_by WHERE message_id = ? AND user_id = ?",
                Integer.class,
                messageId,
                userId);
    }

    private void applyMigration() {
        assertThat(MIGRATION).as("checked-in calendar migration").isRegularFile();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
            return null;
        });
        entityManager.clear();
    }

    private ChatRoom legacyRoom(String id, User host, Instant startsAt) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("Calendar migration meetup");
        room.setDescription("Legacy room profile date");
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setCreator(host);
        room.setParticipants(new HashSet<>(List.of(host)));
        room.setScheduledAt(LocalDateTime.ofInstant(startsAt, ZoneOffset.UTC));
        room.setMeetupTimeBasis(MeetupTimeBasis.UTC);
        room.setDurationMinutes(90);
        return room;
    }

    private ChatSchedule schedule(
            String id,
            ChatRoom room,
            User creator,
            Instant startsAt
    ) {
        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(id);
        schedule.setRoom(room);
        schedule.setCreator(creator);
        schedule.setTitle("Calendar migration meetup");
        schedule.setStartsAt(startsAt);
        schedule.setDurationMinutes(90);
        schedule.setTimeZone("Asia/Seoul");
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        return schedule;
    }

    private User user(String username) {
        return User.builder()
                .email(username + "@example.invalid")
                .username(username)
                .password("test-password-hash")
                .latitude(37.5)
                .longitude(127.0)
                .address("Seoul")
                .build();
    }

    private String deterministicId(String namespace, String value) {
        return UUID.nameUUIDFromBytes(
                (namespace + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
