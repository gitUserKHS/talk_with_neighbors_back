-- Expand-phase data migration. It intentionally leaves every chat_rooms
-- schedule/deadline column untouched so the previous backend remains safe to
-- roll back while ChatSchedule becomes the canonical multi-event calendar.
--
-- The temporary-table DDL runs before the transaction. Candidate room rows are
-- then locked for the atomic snapshot and DML, matching the compatible
-- backend's room-first write lock. A commit-before-ledger retry is idempotent
-- and still repairs a missing host RSVP, schedule card, or read row.

DROP TEMPORARY TABLE IF EXISTS `tmp_legacy_schedule_candidates`;
CREATE TEMPORARY TABLE `tmp_legacy_schedule_candidates` (
    `room_id` VARCHAR(255) NOT NULL,
    `deterministic_schedule_id` CHAR(36) NOT NULL,
    `starts_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`room_id`),
    UNIQUE KEY `uk_tmp_legacy_deterministic_schedule_id` (`deterministic_schedule_id`)
) ENGINE=InnoDB;

DROP TEMPORARY TABLE IF EXISTS `tmp_legacy_schedule_backfill`;
CREATE TEMPORARY TABLE `tmp_legacy_schedule_backfill` (
    `room_id` VARCHAR(255) NOT NULL,
    `schedule_id` CHAR(36) NOT NULL,
    `starts_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`room_id`),
    UNIQUE KEY `uk_tmp_legacy_schedule_id` (`schedule_id`)
) ENGINE=InnoDB;

START TRANSACTION;

INSERT INTO `tmp_legacy_schedule_candidates` (
    `room_id`, `deterministic_schedule_id`, `starts_at`
)
SELECT room.id,
       CONCAT(
           SUBSTRING(MD5(CONCAT('legacy-chat-schedule:', room.id)), 1, 8), '-',
           SUBSTRING(MD5(CONCAT('legacy-chat-schedule:', room.id)), 9, 4), '-3',
           SUBSTRING(MD5(CONCAT('legacy-chat-schedule:', room.id)), 14, 3), '-',
           LOWER(HEX((CONV(SUBSTRING(
               MD5(CONCAT('legacy-chat-schedule:', room.id)), 17, 1), 16, 10) & 3) | 8)),
           SUBSTRING(MD5(CONCAT('legacy-chat-schedule:', room.id)), 18, 3), '-',
           SUBSTRING(MD5(CONCAT('legacy-chat-schedule:', room.id)), 21, 12)
       ),
       CASE
           WHEN room.meetup_time_basis = 'UTC' THEN room.scheduled_at
           ELSE DATE_SUB(room.scheduled_at, INTERVAL 9 HOUR)
       END
FROM chat_rooms room
WHERE room.scheduled_at IS NOT NULL
  AND room.is_public = 1
ORDER BY room.id
FOR UPDATE;

-- A deterministic ID already present means an earlier attempt inserted the
-- schedule before failing. Otherwise an active same-time calendar event is the
-- existing representation; only genuinely missing schedules use the stable ID.
INSERT INTO `tmp_legacy_schedule_backfill` (`room_id`, `schedule_id`, `starts_at`)
SELECT candidate.room_id,
       COALESCE(
           schedule_by_id.id,
           (
               SELECT MIN(schedule_by_time.id)
               FROM chat_schedules schedule_by_time
               WHERE schedule_by_time.room_id = candidate.room_id
                 AND schedule_by_time.status = 'SCHEDULED'
                 AND schedule_by_time.starts_at = candidate.starts_at
           ),
           candidate.deterministic_schedule_id
       ),
       candidate.starts_at
FROM tmp_legacy_schedule_candidates candidate
LEFT JOIN chat_schedules schedule_by_id
       ON schedule_by_id.id = candidate.deterministic_schedule_id
      AND schedule_by_id.room_id = candidate.room_id;

INSERT INTO chat_schedules (
    id, room_id, creator_id, title, description, starts_at,
    duration_minutes, time_zone, location, location_address,
    latitude, longitude, kakao_place_id, status, version,
    created_at, updated_at, cancelled_at
)
SELECT pending.schedule_id,
       room.id,
       room.creator_id,
       LEFT(room.name, 80),
       room.description,
       pending.starts_at,
       COALESCE(room.duration_minutes, 120),
       'Asia/Seoul',
       room.location,
       room.location_address,
       room.latitude,
       room.longitude,
       room.kakao_place_id,
       'SCHEDULED',
       0,
       UTC_TIMESTAMP(6),
       UTC_TIMESTAMP(6),
       NULL
FROM tmp_legacy_schedule_backfill pending
JOIN chat_rooms room ON room.id = pending.room_id
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_schedules existing_schedule
    WHERE existing_schedule.id = pending.schedule_id
);

-- These dependent inserts deliberately run for every resolved candidate, even
-- when the schedule existed before this invocation. That repairs partial runs.
INSERT INTO chat_schedule_rsvps (schedule_id, user_id, status, responded_at)
SELECT pending.schedule_id, room.creator_id, 'ATTENDING', UTC_TIMESTAMP(6)
FROM tmp_legacy_schedule_backfill pending
JOIN chat_rooms room ON room.id = pending.room_id
JOIN chat_schedules schedule
  ON schedule.id = pending.schedule_id
 AND schedule.room_id = pending.room_id
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_schedule_rsvps existing_rsvp
    WHERE existing_rsvp.schedule_id = pending.schedule_id
      AND existing_rsvp.user_id = room.creator_id
);

INSERT INTO messages (
    id, chat_room_id, sender_id, content, created_at, updated_at,
    type, schedule_id, is_deleted
)
SELECT CONCAT(
           SUBSTRING(pending_message.message_hash, 1, 8), '-',
           SUBSTRING(pending_message.message_hash, 9, 4), '-3',
           SUBSTRING(pending_message.message_hash, 14, 3), '-',
           LOWER(HEX((CONV(SUBSTRING(pending_message.message_hash, 17, 1), 16, 10) & 3) | 8)),
           SUBSTRING(pending_message.message_hash, 18, 3), '-',
           SUBSTRING(pending_message.message_hash, 21, 12)
       ),
       room.id,
       room.creator_id,
       CONCAT(CONVERT(0xEC9DBCECA0953A20 USING utf8mb4), LEFT(room.name, 80)),
       DATE_SUB(COALESCE(room.last_message_time, UTC_TIMESTAMP(6)), INTERVAL 1 SECOND),
       DATE_SUB(COALESCE(room.last_message_time, UTC_TIMESTAMP(6)), INTERVAL 1 SECOND),
       'SCHEDULE',
       pending_message.schedule_id,
       0
FROM (
    SELECT pending.*,
           MD5(CONCAT('legacy-chat-schedule-message:', pending.schedule_id)) AS message_hash
    FROM tmp_legacy_schedule_backfill pending
) pending_message
JOIN chat_rooms room ON room.id = pending_message.room_id
JOIN chat_schedules schedule
  ON schedule.id = pending_message.schedule_id
 AND schedule.room_id = pending_message.room_id
WHERE NOT EXISTS (
          SELECT 1
          FROM messages existing_message
          WHERE existing_message.schedule_id = pending_message.schedule_id
      )
  AND NOT EXISTS (
          SELECT 1
          FROM messages existing_message_by_id
          WHERE existing_message_by_id.id = CONCAT(
              SUBSTRING(pending_message.message_hash, 1, 8), '-',
              SUBSTRING(pending_message.message_hash, 9, 4), '-3',
              SUBSTRING(pending_message.message_hash, 14, 3), '-',
              LOWER(HEX((CONV(SUBSTRING(pending_message.message_hash, 17, 1), 16, 10) & 3) | 8)),
              SUBSTRING(pending_message.message_hash, 18, 3), '-',
              SUBSTRING(pending_message.message_hash, 21, 12)
          )
      );

-- The same partial run may already have inserted a deterministic card before a
-- later statement failed. Keep its display metadata aligned with the refreshed
-- deterministic schedule, without modifying cards for unrelated same-time rows.
UPDATE messages message
JOIN tmp_legacy_schedule_backfill pending
  ON pending.schedule_id = message.schedule_id
JOIN tmp_legacy_schedule_candidates candidate
  ON candidate.room_id = pending.room_id
 AND candidate.deterministic_schedule_id = pending.schedule_id
JOIN chat_rooms room
  ON room.id = pending.room_id
SET message.sender_id = room.creator_id,
    message.content = CONCAT(
        CONVERT(0xEC9DBCECA0953A20 USING utf8mb4), LEFT(room.name, 80)),
    message.updated_at = UTC_TIMESTAMP(6)
WHERE message.chat_room_id = room.id
  AND message.type = 'SCHEDULE'
  AND NOT (
    message.sender_id <=> room.creator_id
    AND message.content <=> CONCAT(
        CONVERT(0xEC9DBCECA0953A20 USING utf8mb4), LEFT(room.name, 80))
  );

-- Join by schedule_id rather than the generated ID: an earlier application
-- version may already have created the one-to-one card with a different ID.
INSERT INTO message_read_by (message_id, user_id)
SELECT message.id, room.creator_id
FROM tmp_legacy_schedule_backfill pending
JOIN chat_rooms room ON room.id = pending.room_id
JOIN messages message
  ON message.schedule_id = pending.schedule_id
 AND message.chat_room_id = pending.room_id
WHERE NOT EXISTS (
    SELECT 1
    FROM message_read_by existing_read
    WHERE existing_read.message_id = message.id
      AND existing_read.user_id = room.creator_id
);

COMMIT;

DROP TEMPORARY TABLE `tmp_legacy_schedule_backfill`;
DROP TEMPORARY TABLE `tmp_legacy_schedule_candidates`;
