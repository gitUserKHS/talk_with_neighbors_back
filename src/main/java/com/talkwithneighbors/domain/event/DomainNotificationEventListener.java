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

    @EventListener
    public void onPostCommented(PostCommentedEvent event) {
        if (event.postAuthorId() == null || event.postAuthorId().equals(event.commenterId())) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", event.postId());
        data.put("commentId", event.commentId());
        data.put("actorId", event.commenterId());

        String snippet = event.snippet() == null ? "" : event.snippet();
        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "POST_COMMENTED",
                data,
                displayName(event.commenterName()) + "님이 회원님의 글에 댓글을 남겼어요: " + snippet,
                "/feed/" + event.postId()
        );

        deliver(
                event.postAuthorId(),
                "/queue/system-notifications",
                notification,
                OfflineNotification.NotificationType.POST_COMMENTED,
                4
        );
    }

    @EventListener
    public void onPostLiked(PostLikedEvent event) {
        if (event.postAuthorId() == null || event.postAuthorId().equals(event.likerId())) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("postId", event.postId());
        data.put("actorId", event.likerId());

        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "POST_LIKED",
                data,
                displayName(event.likerName()) + "님이 회원님의 글을 좋아해요",
                "/feed/" + event.postId()
        );

        deliver(
                event.postAuthorId(),
                "/queue/system-notifications",
                notification,
                OfflineNotification.NotificationType.POST_LIKED,
                4
        );
    }

    private String displayName(String name) {
        return name == null || name.isBlank() ? "이웃" : name;
    }

    @EventListener
    public void onChatRoomDeleted(ChatRoomDeletedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("chatRoomId", event.roomId());

        WebSocketNotification<Map<String, Object>> notification = new WebSocketNotification<>(
                "ROOM_DELETED",
                data,
                "채팅방이 삭제되었어.",
                "/chat"
        );

        event.participantIds().forEach(participantId -> {
            redisSessionService.clearUserCurrentRoomIfMatches(
                    participantId.toString(), event.roomId());
            deliver(
                    participantId,
                    "/queue/chat-notifications",
                    notification,
                    OfflineNotification.NotificationType.ROOM_DELETED,
                    8
            );

            // The notification queue shows the toast; the updates queue removes
            // the room from an already-open chat list.
            try {
                if (redisSessionService.isUserOnline(participantId.toString())) {
                    messagingTemplate.convertAndSendToUser(
                            participantId.toString(), "/queue/chat-updates", notification);
                }
            } catch (Exception exception) {
                log.warn("Failed to dispatch chat-room removal update. roomId={}, userId={}",
                        event.roomId(), participantId, exception);
            }
        });
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
            if (saved != null && Boolean.TRUE.equals(saved.getIsSent())) {
                // The save returned an existing row that was already delivered (e.g. a
                // re-like after an unlike); the user has seen it, so do not toast again.
                return;
            }
            if (redisSessionService.isUserOnline(userId.toString())) {
                try {
                    messagingTemplate.convertAndSendToUser(
                            userId.toString(), destination, notification);
                } catch (Exception exception) {
                    // The durable notification stays pending and can be delivered when
                    // the user reconnects; a transient socket failure must not retry
                    // the whole outbox event and duplicate earlier recipients.
                    log.warn("WebSocket delivery failed; offline notification remains pending. "
                                    + "userId={}, destination={}",
                            userId, destination, exception);
                    return;
                }
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
