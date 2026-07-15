package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleRsvp;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.MeetupWaitlistEntry;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.MessageAttachment;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.NotificationService;
import com.talkwithneighbors.service.impl.ChatServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Tag("mysql")
@ActiveProfiles("mysql-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatServiceImpl.class, ChatRoomDeletionRepository.class, TestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatRoomDeletionMySqlIntegrationTest {
    @Autowired
    private ChatService chatService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MeetupWaitlistRepository meetupWaitlistRepository;

    @Autowired
    private ChatScheduleRepository chatScheduleRepository;

    @Autowired
    private ChatScheduleRsvpRepository chatScheduleRsvpRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private DomainEventPublisher domainEventPublisher;

    @Test
    void deletesOneToOneRoomAfterMessageWasSoftDeletedAndReadByBothUsers() {
        Fixture fixture = createFixture(ChatRoomType.ONE_ON_ONE, true, false);

        assertDoesNotThrow(() -> chatService.deleteRoom(fixture.roomId()));

        assertRoomGraphDeleted(fixture);
        ChatException secondDelete = assertThrows(
                ChatException.class,
                () -> chatService.deleteRoom(fixture.roomId())
        );
        assertEquals(HttpStatus.NOT_FOUND, secondDelete.getStatus());
    }

    @Test
    void deletesGroupRoomWithAttachmentInterestTagAndWaitlist() {
        Fixture fixture = createFixture(ChatRoomType.GROUP, false, true);

        assertDoesNotThrow(() -> chatService.deleteRoom(fixture.roomId()));

        assertRoomGraphDeleted(fixture);
    }

    private Fixture createFixture(
            ChatRoomType type,
            boolean softDeletedMessage,
            boolean includeWaitlist
    ) {
        Fixture fixture = transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            User owner = userRepository.save(user("owner-" + suffix));
            User peer = userRepository.save(user("peer-" + suffix));

            ChatRoom room = new ChatRoom();
            room.setId("room-" + suffix);
            room.setName("MySQL deletion regression");
            room.setType(type);
            room.setCreator(owner);
            room.setParticipants(new HashSet<>(List.of(owner, peer)));
            room.setInterestTags(new ArrayList<>(List.of("database")));
            room = chatRoomRepository.saveAndFlush(room);

            Message message = new Message();
            message.setId("message-" + suffix);
            message.setChatRoom(room);
            message.setSender(owner);
            message.setContent(softDeletedMessage ? "" : "attachment message");
            message.setType(softDeletedMessage
                    ? Message.MessageType.SYSTEM
                    : Message.MessageType.IMAGE);
            message.setDeleted(softDeletedMessage);
            if (softDeletedMessage) {
                message.setDeletedAt(LocalDateTime.now());
            } else {
                message.setAttachments(new ArrayList<>(List.of(new MessageAttachment(
                        "/api/media/chat/image.webp",
                        "/api/media/chat/image-thumbnail.webp",
                        ChatAttachmentType.IMAGE,
                        "image/webp",
                        "image.webp",
                        128L,
                        16,
                        16,
                        null
                ))));
            }
            message.setReadByUsers(new HashSet<>(List.of(owner.getId(), peer.getId())));
            messageRepository.saveAndFlush(message);

            if (includeWaitlist) {
                meetupWaitlistRepository.saveAndFlush(new MeetupWaitlistEntry(room, peer));

                ChatSchedule schedule = new ChatSchedule();
                schedule.setId("schedule-" + suffix);
                schedule.setRoom(room);
                schedule.setCreator(owner);
                schedule.setTitle("Deletion-linked schedule");
                schedule.setStartsAt(Instant.now().plusSeconds(86_400));
                schedule.setDurationMinutes(90);
                schedule.setTimeZone("Asia/Seoul");
                schedule.setStatus(ChatScheduleStatus.SCHEDULED);
                schedule = chatScheduleRepository.saveAndFlush(schedule);

                chatScheduleRsvpRepository.saveAndFlush(new ChatScheduleRsvp(
                        schedule, peer, ChatScheduleRsvpStatus.ATTENDING));

                Message scheduleCard = new Message();
                scheduleCard.setId("schedule-message-" + suffix);
                scheduleCard.setChatRoom(room);
                scheduleCard.setSender(owner);
                scheduleCard.setContent("일정: Deletion-linked schedule");
                scheduleCard.setType(Message.MessageType.SCHEDULE);
                scheduleCard.setSchedule(schedule);
                scheduleCard.setReadByUsers(new HashSet<>(List.of(owner.getId())));
                messageRepository.saveAndFlush(scheduleCard);
            }

            entityManager.flush();
            entityManager.clear();
            return new Fixture(room.getId(), message.getId(), owner.getId(), peer.getId());
        });

        assertNotNull(fixture);
        return fixture;
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

    private void assertRoomGraphDeleted(Fixture fixture) {
        assertCount(0, "SELECT COUNT(*) FROM chat_rooms WHERE id = ?", fixture.roomId());
        assertCount(0, "SELECT COUNT(*) FROM messages WHERE chat_room_id = ?", fixture.roomId());
        assertCount(0, "SELECT COUNT(*) FROM message_read_by WHERE message_id = ?", fixture.messageId());
        assertCount(0, "SELECT COUNT(*) FROM message_attachments WHERE message_id = ?", fixture.messageId());
        assertCount(0, "SELECT COUNT(*) FROM chat_room_participants WHERE chat_room_id = ?", fixture.roomId());
        assertCount(0, "SELECT COUNT(*) FROM chat_room_interest_tags WHERE chat_room_id = ?", fixture.roomId());
        assertCount(0, "SELECT COUNT(*) FROM meetup_waitlist WHERE room_id = ?", fixture.roomId());
        assertCount(0, "SELECT COUNT(*) FROM chat_schedules WHERE room_id = ?", fixture.roomId());
        assertCount(0, """
                SELECT COUNT(*) FROM chat_schedule_rsvps
                WHERE schedule_id IN (SELECT id FROM chat_schedules WHERE room_id = ?)
                """, fixture.roomId());
        assertCount(1, "SELECT COUNT(*) FROM users WHERE id = ?", fixture.ownerId());
        assertCount(1, "SELECT COUNT(*) FROM users WHERE id = ?", fixture.peerId());
    }

    private void assertCount(int expected, String sql, Object parameter) {
        Integer actual = jdbcTemplate.queryForObject(sql, Integer.class, parameter);
        assertEquals(expected, actual);
    }

    private record Fixture(String roomId, String messageId, Long ownerId, Long peerId) {
    }
}
