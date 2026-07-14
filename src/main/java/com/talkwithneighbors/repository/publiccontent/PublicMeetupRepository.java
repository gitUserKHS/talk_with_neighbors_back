package com.talkwithneighbors.repository.publiccontent;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomStatus;
import com.talkwithneighbors.entity.ChatRoomType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PublicMeetupRepository extends Repository<ChatRoom, String> {

    @Query(
            value = """
                    SELECT DISTINCT room
                    FROM ChatRoom room
                    LEFT JOIN room.interestTags keywordTag
                    LEFT JOIN room.interestTags interestTag
                    WHERE room.type = :type
                      AND room.publicRoom = true
                      AND room.status = :status
                      AND (
                        :keyword = ''
                        OR LOWER(room.name) LIKE CONCAT('%', :keyword, '%')
                        OR LOWER(COALESCE(room.description, '')) LIKE CONCAT('%', :keyword, '%')
                        OR LOWER(keywordTag) LIKE CONCAT('%', :keyword, '%')
                      )
                      AND (:interest = '' OR LOWER(interestTag) = :interest)
                    ORDER BY
                      CASE WHEN room.scheduledAt IS NULL THEN 1 ELSE 0 END,
                      room.scheduledAt ASC,
                      room.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(DISTINCT room)
                    FROM ChatRoom room
                    LEFT JOIN room.interestTags keywordTag
                    LEFT JOIN room.interestTags interestTag
                    WHERE room.type = :type
                      AND room.publicRoom = true
                      AND room.status = :status
                      AND (
                        :keyword = ''
                        OR LOWER(room.name) LIKE CONCAT('%', :keyword, '%')
                        OR LOWER(COALESCE(room.description, '')) LIKE CONCAT('%', :keyword, '%')
                        OR LOWER(keywordTag) LIKE CONCAT('%', :keyword, '%')
                      )
                      AND (:interest = '' OR LOWER(interestTag) = :interest)
                    """
    )
    Page<ChatRoom> findPublicMeetups(
            @Param("type") ChatRoomType type,
            @Param("status") ChatRoomStatus status,
            @Param("keyword") String keyword,
            @Param("interest") String interest,
            Pageable pageable
    );
}
