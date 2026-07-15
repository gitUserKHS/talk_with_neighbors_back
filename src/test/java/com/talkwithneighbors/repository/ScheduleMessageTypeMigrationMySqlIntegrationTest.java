package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleStatus;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
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
class ScheduleMessageTypeMigrationMySqlIntegrationTest {
    private static final Path MIGRATION = Path.of(
            "deploy",
            "k8s",
            "database-migrations",
            "V2026071501__migrate_message_type_to_varchar.sql"
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
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationUpgradesLegacyMessageEnumAndPersistsScheduleCard() {
        assertThat(messageTypeColumn()).isEqualTo("varchar(20)");

        jdbcTemplate.execute("""
                ALTER TABLE messages
                MODIFY COLUMN type ENUM(
                    'ENTER', 'FILE', 'IMAGE', 'LEAVE', 'SYSTEM', 'TEXT', 'VIDEO'
                ) NOT NULL
                """);
        try {
            assertThat(messageTypeColumn()).isEqualTo(
                    "enum('ENTER','FILE','IMAGE','LEAVE','SYSTEM','TEXT','VIDEO')"
            );

            assertThat(MIGRATION)
                    .as("checked-in database migration")
                    .isRegularFile();
            applyMigration();

            assertThat(messageTypeColumn()).isEqualTo("varchar(20)");
            applyMigration();
            assertThat(messageTypeColumn()).isEqualTo("varchar(20)");

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            User host = userRepository.saveAndFlush(user("migration-host-" + suffix));

            ChatRoom room = new ChatRoom();
            room.setId("migration-room-" + suffix);
            room.setName("schedule enum migration");
            room.setType(ChatRoomType.GROUP);
            room.setCreator(host);
            room.setParticipants(new HashSet<>(List.of(host)));
            room = chatRoomRepository.saveAndFlush(room);

            ChatSchedule schedule = new ChatSchedule();
            schedule.setId(UUID.randomUUID().toString());
            schedule.setRoom(room);
            schedule.setCreator(host);
            schedule.setTitle("마이그레이션 후 약속");
            schedule.setStartsAt(Instant.now().plusSeconds(86_400));
            schedule.setDurationMinutes(60);
            schedule.setTimeZone("Asia/Seoul");
            schedule.setStatus(ChatScheduleStatus.SCHEDULED);
            schedule = chatScheduleRepository.saveAndFlush(schedule);

            Message card = new Message();
            card.setId("migration-card-" + suffix);
            card.setChatRoom(room);
            card.setSender(host);
            card.setContent("일정: 마이그레이션 후 약속");
            card.setType(Message.MessageType.SCHEDULE);
            card.setSchedule(schedule);
            messageRepository.saveAndFlush(card);
            entityManager.clear();

            assertThat(messageRepository.findById(card.getId()))
                    .get()
                    .extracting(Message::getType)
                    .isEqualTo(Message.MessageType.SCHEDULE);
            assertThat(messageRepository.findBySchedule_IdAndChatRoom_Id(schedule.getId(), room.getId()))
                    .get()
                    .extracting(Message::getId)
                    .isEqualTo(card.getId());
        } finally {
            jdbcTemplate.execute("ALTER TABLE messages MODIFY COLUMN type VARCHAR(20) NOT NULL");
        }
    }

    private String messageTypeColumn() {
        return jdbcTemplate.queryForObject("""
                SELECT COLUMN_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'messages'
                  AND COLUMN_NAME = 'type'
                """, String.class);
    }

    private void applyMigration() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
            return null;
        });
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
}
