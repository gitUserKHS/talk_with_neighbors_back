package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.notification.NotificationInboxDto;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.OfflineNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationInboxService {
    private final OfflineNotificationRepository repository;

    @Transactional(readOnly = true)
    public Page<NotificationInboxDto> getNotifications(Long userId, Pageable pageable) {
        return repository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(userId, LocalDateTime.now(), pageable)
                .map(NotificationInboxDto::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNullAndExpiresAtAfter(userId, LocalDateTime.now());
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        if (repository.markAsRead(notificationId, userId, LocalDateTime.now()) == 0
                && repository.findByIdAndUserId(notificationId, userId).isEmpty()) {
            throw new MatchingException("알림을 찾을 수 없어요.", HttpStatus.NOT_FOUND);
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllAsRead(userId, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long userId, Long notificationId) {
        repository.findByIdAndUserId(notificationId, userId)
                .ifPresentOrElse(repository::delete, () -> {
                    throw new MatchingException("알림을 찾을 수 없어요.", HttpStatus.NOT_FOUND);
                });
    }
}
