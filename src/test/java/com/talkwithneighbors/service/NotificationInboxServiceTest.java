package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.OfflineNotification;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.repository.OfflineNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationInboxServiceTest {
    @Mock OfflineNotificationRepository repository;
    private NotificationInboxService service;

    @BeforeEach void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationInboxService(repository);
    }

    @Test void returnsOnlyCurrentUsersNotificationsFromRepository() {
        OfflineNotification notification = new OfflineNotification();
        notification.setId(7L);
        notification.setUserId(1L);
        notification.setType(OfflineNotification.NotificationType.SYSTEM_NOTICE);
        notification.setData("{}");
        notification.setCreatedAt(LocalDateTime.now());
        when(repository.findByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(notification)));

        var page = service.getNotifications(1L, PageRequest.of(0, 20));
        assertEquals(7L, page.getContent().get(0).id());
    }

    @Test void cannotReadAnotherUsersNotification() {
        when(repository.markAsRead(any(), any(), any())).thenReturn(0);
        when(repository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        MatchingException exception = assertThrows(MatchingException.class, () -> service.markRead(1L, 9L));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test void marksAllNotificationsReadForUser() {
        service.markAllRead(3L);
        verify(repository).markAllAsRead(any(), any());
    }
}
