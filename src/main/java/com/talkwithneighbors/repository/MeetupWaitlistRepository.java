package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.MeetupWaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MeetupWaitlistRepository extends JpaRepository<MeetupWaitlistEntry, Long> {
    boolean existsByRoom_IdAndUser_Id(String roomId, Long userId);
    long countByRoom_Id(String roomId);
    Optional<MeetupWaitlistEntry> findFirstByRoom_IdOrderByCreatedAtAsc(String roomId);
    List<MeetupWaitlistEntry> findByRoom_IdOrderByCreatedAtAsc(String roomId);
    void deleteByRoom_IdAndUser_Id(String roomId, Long userId);
}
