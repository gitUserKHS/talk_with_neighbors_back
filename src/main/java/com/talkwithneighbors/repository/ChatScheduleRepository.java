package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.talkwithneighbors.entity.ChatScheduleStatus;

public interface ChatScheduleRepository extends JpaRepository<ChatSchedule, String> {
    boolean existsByRoom_Id(String roomId);

    boolean existsByRoom_IdAndStatusAndStartsAt(
            String roomId,
            ChatScheduleStatus status,
            Instant startsAt);

    boolean existsByRoom_IdAndCreator_IdAndStatusAndStartsAtAfter(
            String roomId,
            Long creatorId,
            ChatScheduleStatus status,
            Instant startsAt);

    Optional<ChatSchedule> findFirstByRoom_IdAndStatusAndStartsAtAfterOrderByStartsAtAscIdAsc(
            String roomId,
            ChatScheduleStatus status,
            Instant startsAt);

    Optional<ChatSchedule> findFirstByRoom_IdAndStatusOrderByStartsAtDescIdDesc(
            String roomId,
            ChatScheduleStatus status);

    @EntityGraph(attributePaths = {"creator", "rsvps", "rsvps.user"})
    @Query("""
            SELECT DISTINCT schedule
            FROM ChatSchedule schedule
            WHERE schedule.room.id = :roomId
            ORDER BY
              CASE WHEN schedule.status = com.talkwithneighbors.entity.ChatScheduleStatus.SCHEDULED THEN 0 ELSE 1 END,
              schedule.startsAt ASC,
              schedule.id ASC
            """)
    List<ChatSchedule> findDetailedByRoomId(@Param("roomId") String roomId);

    @EntityGraph(attributePaths = {"creator", "rsvps", "rsvps.user"})
    @Query("""
            SELECT schedule
            FROM ChatSchedule schedule
            WHERE schedule.id = :scheduleId AND schedule.room.id = :roomId
            """)
    Optional<ChatSchedule> findDetailedByIdAndRoomId(
            @Param("scheduleId") String scheduleId,
            @Param("roomId") String roomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT schedule
            FROM ChatSchedule schedule
            WHERE schedule.id = :scheduleId AND schedule.room.id = :roomId
            """)
    Optional<ChatSchedule> findLockedByIdAndRoomId(
            @Param("scheduleId") String scheduleId,
            @Param("roomId") String roomId);
}
