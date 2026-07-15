package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatScheduleRsvp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatScheduleRsvpRepository extends JpaRepository<ChatScheduleRsvp, Long> {
    Optional<ChatScheduleRsvp> findBySchedule_IdAndUser_Id(String scheduleId, Long userId);

    long deleteBySchedule_Room_IdAndUser_Id(String roomId, Long userId);
}
