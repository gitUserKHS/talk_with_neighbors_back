package com.talkwithneighbors.domain.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.dto.notification.WebSocketNotification;
import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.service.OfflineNotificationService;
import com.talkwithneighbors.service.RedisSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DomainNotificationEventListener {
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisSessionService redisSessionService;
    private final OfflineNotificationService offlineNotificationService;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onMatchCompleted(MatchCompletedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("matchId", event.matchId());
        data.put("chatRoomId", event.chatRoomId());

        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "MATCH_COMPLETED_AND_CHAT_CREATED",
                data,
                "매칭이 성사되어 채팅방이 열렸어요.",
                "/chat/" + event.chatRoomId()
        );

        deliver(
                event.user1Id(),
                "/queue/match-notifications",
                notification,
                OfflineNotification.NotificationType.MATCH_ACCEPTED,
                10
        );
        deliver(
                event.user2Id(),
                "/queue/match-notifications",
                notification,
                OfflineNotification.NotificationType.MATCH_ACCEPTED,
                10
        );
    }

    @EventListener
    public void onMeetupJoined(MeetupJoinedEvent event) {
        if (event.creatorId() == null || event.creatorId().equals(event.joinedUserId())) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("meetupId", event.meetupId());
        data.put("meetupTitle", event.meetupTitle());
        data.put("joinedUserId", event.joinedUserId());

        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "MEETUP_JOINED",
                data,
                "새로운 이웃이 모임에 참가했어요.",
                "/meetups"
        );

        deliver(
                event.creatorId(),
                "/queue/system-notifications",
                notification,
                OfflineNotification.NotificationType.SYSTEM_NOTICE,
                5
        );
    }

    private void deliver(
            Long userId,
            String destination,
            WebSocketNotification<?> notification,
            OfflineNotification.NotificationType offlineType,
            int priority
    ) {
        if (userId == null) {
            return;
        }

        try {
            OfflineNotification saved = offlineNotificationService.saveOfflineNotification(
                    userId,
                    offlineType,
                    objectMapper.writeValueAsString(notification.getData()),
                    notification.getMessage(),
                    notification.getNavigateTo(),
                    priority
            );
            if (redisSessionService.isUserOnline(userId.toString())) {
                messagingTemplate.convertAndSendToUser(userId.toString(), destination, notification);
                if (saved != null) {
                    offlineNotificationService.markAsDelivered(saved.getId());
                }
            }
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize notification for domain event. userId={}", userId, exception);
            throw new IllegalStateException("Failed to serialize domain notification", exception);
        }
    }
}
