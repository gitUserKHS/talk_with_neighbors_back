package com.talkwithneighbors.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Deletes the complete database graph owned by a chat room in foreign-key order.
 *
 * <p>The regular JPA entity delete is deliberately not used here. Chat messages own
 * two element-collection tables, while meetup rooms can also own wait-list rows.
 * Mixing managed entities with native and JPQL bulk deletes leaves the persistence
 * context stale and can defer a constraint failure until transaction commit.</p>
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ChatRoomDeletionRepository {
    private final EntityManager entityManager;

    public List<String> findMediaUrlsByRoomId(String roomId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT a.media_url, a.thumbnail_url
                        FROM message_attachments a
                        INNER JOIN messages m ON m.id = a.message_id
                        WHERE m.chat_room_id = :roomId
                        ORDER BY a.message_id, a.sort_order
                        """)
                .setParameter("roomId", roomId)
                .getResultList();

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Object[] row : rows) {
            addUrl(urls, row[0]);
            addUrl(urls, row[1]);
        }
        return List.copyOf(urls);
    }

    public ChatRoomDeletionResult deleteByRoomId(String roomId) {
        // Make every pending entity change visible before executing bulk DML.
        entityManager.flush();

        int waitlistEntries = executeDelete(
                "DELETE FROM meetup_waitlist WHERE room_id = :roomId", roomId);
        int readStatuses = executeDelete("""
                DELETE FROM message_read_by
                WHERE message_id IN (
                    SELECT id FROM messages WHERE chat_room_id = :roomId
                )
                """, roomId);
        int attachments = executeDelete("""
                DELETE FROM message_attachments
                WHERE message_id IN (
                    SELECT id FROM messages WHERE chat_room_id = :roomId
                )
                """, roomId);
        int messages = executeDelete(
                "DELETE FROM messages WHERE chat_room_id = :roomId", roomId);
        int interestTags = executeDelete(
                "DELETE FROM chat_room_interest_tags WHERE chat_room_id = :roomId", roomId);
        int participants = executeDelete(
                "DELETE FROM chat_room_participants WHERE chat_room_id = :roomId", roomId);
        int rooms = executeDelete(
                "DELETE FROM chat_rooms WHERE id = :roomId", roomId);

        // Native DML bypasses the first-level cache; detach stale room/message entities.
        entityManager.clear();
        return new ChatRoomDeletionResult(
                waitlistEntries,
                readStatuses,
                attachments,
                messages,
                interestTags,
                participants,
                rooms
        );
    }

    private int executeDelete(String sql, String roomId) {
        return entityManager.createNativeQuery(sql)
                .setParameter("roomId", roomId)
                .executeUpdate();
    }

    private void addUrl(LinkedHashSet<String> urls, Object candidate) {
        if (candidate != null) {
            String url = candidate.toString();
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
    }

    public record ChatRoomDeletionResult(
            int waitlistEntries,
            int readStatuses,
            int attachments,
            int messages,
            int interestTags,
            int participants,
            int rooms
    ) {
    }
}
