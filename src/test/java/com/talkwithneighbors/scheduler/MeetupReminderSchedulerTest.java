package com.talkwithneighbors.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talkwithneighbors.entity.MeetupTimeBasis;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.service.OfflineNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetupReminderSchedulerTest {
    @Mock
    ChatRoomRepository chatRoomRepository;

    @Mock
    OfflineNotificationService offlineNotificationService;

    @Test
    void queriesUtcAndLegacySeoulWindowsWithoutShiftingExistingRows() {
        ArgumentCaptor<LocalDateTime> utcStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> utcEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> legacyStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> legacyEndCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(chatRoomRepository.findUpcomingPublicMeetups(
                utcStartCaptor.capture(),
                utcEndCaptor.capture(),
                legacyStartCaptor.capture(),
                legacyEndCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(MeetupTimeBasis.UTC),
                org.mockito.ArgumentMatchers.eq(MeetupTimeBasis.LEGACY_ASIA_SEOUL)))
                .thenReturn(List.of());
        LocalDateTime beforeUtc = LocalDateTime.now(ZoneOffset.UTC);

        new MeetupReminderScheduler(chatRoomRepository, offlineNotificationService, new ObjectMapper())
                .createUpcomingReminders();

        LocalDateTime afterUtc = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(utcStartCaptor.getValue()).isBetween(beforeUtc, afterUtc);
        assertThat(Duration.between(utcStartCaptor.getValue(), utcEndCaptor.getValue()))
                .isEqualTo(Duration.ofHours(24));
        assertThat(Duration.between(legacyStartCaptor.getValue(), legacyEndCaptor.getValue()))
                .isEqualTo(Duration.ofHours(24));
        assertThat(Duration.between(utcStartCaptor.getValue(), legacyStartCaptor.getValue()))
                .isEqualTo(Duration.ofHours(9));
    }
}
