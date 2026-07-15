package com.talkwithneighbors.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.MeetupTimePolicy;
import com.talkwithneighbors.service.ChatScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MeetupReminderScheduler {
    private final ChatRoomRepository chatRoomRepository;
    private final ChatScheduleService chatScheduleService;
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void createUpcomingReminders() {
        Instant now = Instant.now();
        chatScheduleService.reconcilePublicMeetupProjections(now);
        LocalDateTime utcStart = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDateTime legacyStart = LocalDateTime.ofInstant(now, MeetupTimePolicy.LEGACY_ZONE);
        for (ChatRoom room : chatRoomRepository.findUpcomingPublicMeetups(
                utcStart,
                utcStart.plusHours(24),
                legacyStart,
                legacyStart.plusHours(24),
                MeetupTimeBasis.UTC,
                MeetupTimeBasis.LEGACY_ASIA_SEOUL)) {
            room.getParticipants().forEach(user -> {
                try {
                    offlineNotificationService.saveOfflineNotification(
                            user.getId(), OfflineNotification.NotificationType.MEETUP_REMINDER,
                            objectMapper.writeValueAsString(Map.of(
                                    "roomId", room.getId(), "title", room.getName(), "scheduledAt",
                                    MeetupTimePolicy.toUtcOffset(
                                            room.getScheduledAt(), room.getMeetupTimeBasis()))),
                            "내일 예정된 '" + room.getName() + "' 모임을 잊지 마.",
                            "/meetups", 9);
                    offlineNotificationService.sendPendingNotifications(user.getId());
                } catch (Exception exception) {
                    log.warn("Failed to create meetup reminder. roomId={}, userId={}", room.getId(), user.getId(), exception);
                }
            });
            room.setReminderSentAt(utcStart);
            chatRoomRepository.save(room);
        }
    }
}
