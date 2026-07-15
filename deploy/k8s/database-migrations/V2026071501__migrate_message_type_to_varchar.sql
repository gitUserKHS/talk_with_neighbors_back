SET @messages_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
);

SET @message_type_migration = IF(
    @messages_table_exists = 0,
    'SELECT 1',
    'ALTER TABLE `messages` MODIFY COLUMN `type` VARCHAR(20) NOT NULL'
);

PREPARE message_type_migration_statement FROM @message_type_migration;
EXECUTE message_type_migration_statement;
DEALLOCATE PREPARE message_type_migration_statement;
