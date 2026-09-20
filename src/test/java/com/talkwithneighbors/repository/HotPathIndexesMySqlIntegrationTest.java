package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Tag("mysql")
@ActiveProfiles("mysql-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HotPathIndexesMySqlIntegrationTest {
    private static final Path MIGRATION = Path.of(
            "deploy",
            "k8s",
            "database-migrations",
            "V2026092001__add_hot_path_indexes.sql"
    );

    /**
     * Every index the ledger file creates, keyed by table. The entity mirrors carry the
     * same names, so Hibernate's create-drop schema already contains most of them.
     */
    private static final Map<String, List<String>> EXPECTED_INDEXES = Map.ofEntries(
            Map.entry("offline_notifications", List.of(
                    "idx_offline_notifications_user_expires_created",
                    "idx_offline_notifications_user_sent_expires"
            )),
            Map.entry("messages", List.of("idx_messages_room_created")),
            Map.entry("message_read_by", List.of("idx_message_read_by_user_message")),
            Map.entry("message_attachments", List.of(
                    "idx_message_attachments_media_url",
                    "idx_message_attachments_thumbnail_url"
            )),
            Map.entry("matches", List.of(
                    "idx_matches_status_expires",
                    "idx_matches_users_status"
            )),
            Map.entry("users", List.of("idx_users_online_last")),
            Map.entry("sessions", List.of("idx_sessions_expires")),
            Map.entry("feed_posts", List.of(
                    "idx_feed_posts_created_id",
                    "idx_feed_posts_public_created",
                    "idx_feed_posts_author_created"
            )),
            Map.entry("chat_rooms", List.of(
                    "idx_chat_rooms_last_message_time",
                    "idx_chat_rooms_public_type_scheduled"
            )),
            Map.entry("post_comments", List.of("idx_post_comments_post_created")),
            Map.entry("post_likes", List.of("idx_post_likes_user_created"))
    );

    /**
     * Indexes whose leading column backs no foreign key. These are dropped first so the
     * migration's ADD INDEX branch is exercised for real; the remaining ones (for example
     * {@code idx_messages_room_created} on {@code chat_room_id}) may have been adopted by a
     * foreign key constraint and MySQL refuses to drop such an index.
     */
    private static final Map<String, List<String>> DROPPABLE_INDEXES = Map.ofEntries(
            Map.entry("offline_notifications", List.of(
                    "idx_offline_notifications_user_expires_created",
                    "idx_offline_notifications_user_sent_expires"
            )),
            Map.entry("message_read_by", List.of("idx_message_read_by_user_message")),
            Map.entry("message_attachments", List.of(
                    "idx_message_attachments_media_url",
                    "idx_message_attachments_thumbnail_url"
            )),
            Map.entry("matches", List.of("idx_matches_status_expires")),
            Map.entry("users", List.of("idx_users_online_last")),
            Map.entry("sessions", List.of("idx_sessions_expires")),
            Map.entry("feed_posts", List.of(
                    "idx_feed_posts_created_id",
                    "idx_feed_posts_public_created"
            )),
            Map.entry("chat_rooms", List.of(
                    "idx_chat_rooms_last_message_time",
                    "idx_chat_rooms_public_type_scheduled"
            ))
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesHotPathIndexesAndIsIdempotent() {
        assertThat(MIGRATION)
                .as("checked-in database migration")
                .isRegularFile();

        DROPPABLE_INDEXES.forEach((table, indexes) -> indexes.forEach(index -> {
            if (indexExists(table, index)) {
                jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
            }
            assertThat(indexExists(table, index))
                    .as("%s.%s before migration", table, index)
                    .isFalse();
        }));

        applyMigration();
        assertAllIndexesExist();

        applyMigration();
        assertAllIndexesExist();

        assertThat(indexColumns("message_attachments", "idx_message_attachments_media_url"))
                .containsExactly("media_url:191");
        assertThat(indexColumns("message_read_by", "idx_message_read_by_user_message"))
                .containsExactly("user_id:null", "message_id:null");
    }

    private void assertAllIndexesExist() {
        EXPECTED_INDEXES.forEach((table, indexes) -> indexes.forEach(index ->
                assertThat(indexExists(table, index))
                        .as("%s.%s", table, index)
                        .isTrue()));
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, table, index);
        return count != null && count > 0;
    }

    private List<String> indexColumns(String table, String index) {
        return jdbcTemplate.query("""
                SELECT CONCAT(COLUMN_NAME, ':', IFNULL(SUB_PART, 'null'))
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                ORDER BY SEQ_IN_INDEX
                """, (rs, rowNum) -> rs.getString(1), table, index);
    }

    private void applyMigration() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
            return null;
        });
    }
}
