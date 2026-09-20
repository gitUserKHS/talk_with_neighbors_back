-- Hot-path secondary indexes for the scheduler scans, unread counts, room history,
-- offline notification lookups and feed/timeline pages that previously ran unindexed.
-- Every block is idempotent: a missing table or an already-present index becomes SELECT 1,
-- and each ADD INDEX runs as online DDL (ALGORITHM=INPLACE, LOCK=NONE).

SET @offline_notifications_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'offline_notifications'
);

SET @idx_offline_notifications_user_expires_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'offline_notifications'
      AND index_name = 'idx_offline_notifications_user_expires_created'
);

SET @idx_offline_notifications_user_expires_created = IF(
    @offline_notifications_table_exists = 0 OR @idx_offline_notifications_user_expires_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `offline_notifications` ADD INDEX `idx_offline_notifications_user_expires_created` (`user_id`, `expires_at`, `created_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_offline_notifications_user_expires_created_statement FROM @idx_offline_notifications_user_expires_created;
EXECUTE idx_offline_notifications_user_expires_created_statement;
DEALLOCATE PREPARE idx_offline_notifications_user_expires_created_statement;

SET @idx_offline_notifications_user_sent_expires_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'offline_notifications'
      AND index_name = 'idx_offline_notifications_user_sent_expires'
);

SET @idx_offline_notifications_user_sent_expires = IF(
    @offline_notifications_table_exists = 0 OR @idx_offline_notifications_user_sent_expires_exists > 0,
    'SELECT 1',
    'ALTER TABLE `offline_notifications` ADD INDEX `idx_offline_notifications_user_sent_expires` (`user_id`, `is_sent`, `expires_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_offline_notifications_user_sent_expires_statement FROM @idx_offline_notifications_user_sent_expires;
EXECUTE idx_offline_notifications_user_sent_expires_statement;
DEALLOCATE PREPARE idx_offline_notifications_user_sent_expires_statement;

SET @messages_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
);

SET @idx_messages_room_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND index_name = 'idx_messages_room_created'
);

SET @idx_messages_room_created = IF(
    @messages_table_exists = 0 OR @idx_messages_room_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `messages` ADD INDEX `idx_messages_room_created` (`chat_room_id`, `created_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_messages_room_created_statement FROM @idx_messages_room_created;
EXECUTE idx_messages_room_created_statement;
DEALLOCATE PREPARE idx_messages_room_created_statement;

SET @message_read_by_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'message_read_by'
);

SET @idx_message_read_by_user_message_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'message_read_by'
      AND index_name = 'idx_message_read_by_user_message'
);

SET @idx_message_read_by_user_message = IF(
    @message_read_by_table_exists = 0 OR @idx_message_read_by_user_message_exists > 0,
    'SELECT 1',
    'ALTER TABLE `message_read_by` ADD INDEX `idx_message_read_by_user_message` (`user_id`, `message_id`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_message_read_by_user_message_statement FROM @idx_message_read_by_user_message;
EXECUTE idx_message_read_by_user_message_statement;
DEALLOCATE PREPARE idx_message_read_by_user_message_statement;

SET @message_attachments_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'message_attachments'
);

SET @idx_message_attachments_media_url_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'message_attachments'
      AND index_name = 'idx_message_attachments_media_url'
);

SET @idx_message_attachments_media_url = IF(
    @message_attachments_table_exists = 0 OR @idx_message_attachments_media_url_exists > 0,
    'SELECT 1',
    'ALTER TABLE `message_attachments` ADD INDEX `idx_message_attachments_media_url` (`media_url`(191)), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_message_attachments_media_url_statement FROM @idx_message_attachments_media_url;
EXECUTE idx_message_attachments_media_url_statement;
DEALLOCATE PREPARE idx_message_attachments_media_url_statement;

SET @idx_message_attachments_thumbnail_url_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'message_attachments'
      AND index_name = 'idx_message_attachments_thumbnail_url'
);

SET @idx_message_attachments_thumbnail_url = IF(
    @message_attachments_table_exists = 0 OR @idx_message_attachments_thumbnail_url_exists > 0,
    'SELECT 1',
    'ALTER TABLE `message_attachments` ADD INDEX `idx_message_attachments_thumbnail_url` (`thumbnail_url`(191)), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_message_attachments_thumbnail_url_statement FROM @idx_message_attachments_thumbnail_url;
EXECUTE idx_message_attachments_thumbnail_url_statement;
DEALLOCATE PREPARE idx_message_attachments_thumbnail_url_statement;

SET @matches_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'matches'
);

SET @idx_matches_status_expires_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'matches'
      AND index_name = 'idx_matches_status_expires'
);

SET @idx_matches_status_expires = IF(
    @matches_table_exists = 0 OR @idx_matches_status_expires_exists > 0,
    'SELECT 1',
    'ALTER TABLE `matches` ADD INDEX `idx_matches_status_expires` (`status`, `expires_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_matches_status_expires_statement FROM @idx_matches_status_expires;
EXECUTE idx_matches_status_expires_statement;
DEALLOCATE PREPARE idx_matches_status_expires_statement;

SET @idx_matches_users_status_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'matches'
      AND index_name = 'idx_matches_users_status'
);

SET @idx_matches_users_status = IF(
    @matches_table_exists = 0 OR @idx_matches_users_status_exists > 0,
    'SELECT 1',
    'ALTER TABLE `matches` ADD INDEX `idx_matches_users_status` (`user1_id`, `user2_id`, `status`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_matches_users_status_statement FROM @idx_matches_users_status;
EXECUTE idx_matches_users_status_statement;
DEALLOCATE PREPARE idx_matches_users_status_statement;

SET @users_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
);

SET @idx_users_online_last_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND index_name = 'idx_users_online_last'
);

SET @idx_users_online_last = IF(
    @users_table_exists = 0 OR @idx_users_online_last_exists > 0,
    'SELECT 1',
    'ALTER TABLE `users` ADD INDEX `idx_users_online_last` (`is_online`, `last_online_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_users_online_last_statement FROM @idx_users_online_last;
EXECUTE idx_users_online_last_statement;
DEALLOCATE PREPARE idx_users_online_last_statement;

SET @sessions_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'sessions'
);

SET @idx_sessions_expires_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'sessions'
      AND index_name = 'idx_sessions_expires'
);

SET @idx_sessions_expires = IF(
    @sessions_table_exists = 0 OR @idx_sessions_expires_exists > 0,
    'SELECT 1',
    'ALTER TABLE `sessions` ADD INDEX `idx_sessions_expires` (`expires_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_sessions_expires_statement FROM @idx_sessions_expires;
EXECUTE idx_sessions_expires_statement;
DEALLOCATE PREPARE idx_sessions_expires_statement;

SET @feed_posts_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'feed_posts'
);

SET @idx_feed_posts_created_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'feed_posts'
      AND index_name = 'idx_feed_posts_created_id'
);

SET @idx_feed_posts_created_id = IF(
    @feed_posts_table_exists = 0 OR @idx_feed_posts_created_id_exists > 0,
    'SELECT 1',
    'ALTER TABLE `feed_posts` ADD INDEX `idx_feed_posts_created_id` (`created_at`, `id`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_feed_posts_created_id_statement FROM @idx_feed_posts_created_id;
EXECUTE idx_feed_posts_created_id_statement;
DEALLOCATE PREPARE idx_feed_posts_created_id_statement;

SET @idx_feed_posts_public_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'feed_posts'
      AND index_name = 'idx_feed_posts_public_created'
);

SET @idx_feed_posts_public_created = IF(
    @feed_posts_table_exists = 0 OR @idx_feed_posts_public_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `feed_posts` ADD INDEX `idx_feed_posts_public_created` (`public_preview`, `created_at`, `id`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_feed_posts_public_created_statement FROM @idx_feed_posts_public_created;
EXECUTE idx_feed_posts_public_created_statement;
DEALLOCATE PREPARE idx_feed_posts_public_created_statement;

SET @idx_feed_posts_author_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'feed_posts'
      AND index_name = 'idx_feed_posts_author_created'
);

SET @idx_feed_posts_author_created = IF(
    @feed_posts_table_exists = 0 OR @idx_feed_posts_author_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `feed_posts` ADD INDEX `idx_feed_posts_author_created` (`author_id`, `created_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_feed_posts_author_created_statement FROM @idx_feed_posts_author_created;
EXECUTE idx_feed_posts_author_created_statement;
DEALLOCATE PREPARE idx_feed_posts_author_created_statement;

SET @chat_rooms_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_rooms'
);

SET @idx_chat_rooms_last_message_time_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_rooms'
      AND index_name = 'idx_chat_rooms_last_message_time'
);

SET @idx_chat_rooms_last_message_time = IF(
    @chat_rooms_table_exists = 0 OR @idx_chat_rooms_last_message_time_exists > 0,
    'SELECT 1',
    'ALTER TABLE `chat_rooms` ADD INDEX `idx_chat_rooms_last_message_time` (`last_message_time`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_chat_rooms_last_message_time_statement FROM @idx_chat_rooms_last_message_time;
EXECUTE idx_chat_rooms_last_message_time_statement;
DEALLOCATE PREPARE idx_chat_rooms_last_message_time_statement;

SET @idx_chat_rooms_public_type_scheduled_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_rooms'
      AND index_name = 'idx_chat_rooms_public_type_scheduled'
);

SET @idx_chat_rooms_public_type_scheduled = IF(
    @chat_rooms_table_exists = 0 OR @idx_chat_rooms_public_type_scheduled_exists > 0,
    'SELECT 1',
    'ALTER TABLE `chat_rooms` ADD INDEX `idx_chat_rooms_public_type_scheduled` (`is_public`, `type`, `scheduled_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_chat_rooms_public_type_scheduled_statement FROM @idx_chat_rooms_public_type_scheduled;
EXECUTE idx_chat_rooms_public_type_scheduled_statement;
DEALLOCATE PREPARE idx_chat_rooms_public_type_scheduled_statement;

SET @post_comments_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'post_comments'
);

SET @idx_post_comments_post_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'post_comments'
      AND index_name = 'idx_post_comments_post_created'
);

SET @idx_post_comments_post_created = IF(
    @post_comments_table_exists = 0 OR @idx_post_comments_post_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `post_comments` ADD INDEX `idx_post_comments_post_created` (`post_id`, `created_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_post_comments_post_created_statement FROM @idx_post_comments_post_created;
EXECUTE idx_post_comments_post_created_statement;
DEALLOCATE PREPARE idx_post_comments_post_created_statement;

SET @post_likes_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'post_likes'
);

SET @idx_post_likes_user_created_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'post_likes'
      AND index_name = 'idx_post_likes_user_created'
);

SET @idx_post_likes_user_created = IF(
    @post_likes_table_exists = 0 OR @idx_post_likes_user_created_exists > 0,
    'SELECT 1',
    'ALTER TABLE `post_likes` ADD INDEX `idx_post_likes_user_created` (`user_id`, `created_at`), ALGORITHM=INPLACE, LOCK=NONE'
);

PREPARE idx_post_likes_user_created_statement FROM @idx_post_likes_user_created;
EXECUTE idx_post_likes_user_created_statement;
DEALLOCATE PREPARE idx_post_likes_user_created_statement;
