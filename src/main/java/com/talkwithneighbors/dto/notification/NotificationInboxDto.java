package com.talkwithneighbors.dto.notification;

import com.talkwithneighbors.entity.OfflineNotification;
import java.time.LocalDateTime;

public record NotificationInboxDto(
        Long id,
        OfflineNotification.NotificationType type,
        String data,
        String message,
        String actionUrl,
        Integer priority,
        LocalDateTime createdAt,
        LocalDateTime deliveredAt,
        LocalDateTime readAt
) {
    public static NotificationInboxDto from(OfflineNotification notification) {
        return new NotificationInboxDto(notification.getId(), notification.getType(), notification.getData(),
                notification.getMessage(), notification.getActionUrl(), notification.getPriority(),
                notification.getCreatedAt(), notification.getDeliveredAt(), notification.getReadAt());
    }
}
