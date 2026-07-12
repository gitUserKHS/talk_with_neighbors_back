package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.OfflineNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {
    @Mock
    private OfflineNotificationService offlineNotificationService;

    @Mock
    private Principal principal;

    @InjectMocks
    private NotificationController notificationController;

    @Test
    void clientReadyDeliversPendingNotificationsForAuthenticatedUser() {
        when(principal.getName()).thenReturn("42");

        notificationController.handleClientReady(principal);

        verify(offlineNotificationService).sendPendingNotifications(42L);
    }

    @Test
    void clientReadyWithoutPrincipalDoesNothing() {
        notificationController.handleClientReady(null);

        verify(offlineNotificationService, never()).sendPendingNotifications(42L);
    }
}
