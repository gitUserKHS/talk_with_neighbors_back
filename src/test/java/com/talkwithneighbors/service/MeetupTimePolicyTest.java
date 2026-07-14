package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.MeetupTimeBasis;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MeetupTimePolicyTest {
    @Test
    void treatsUnmarkedPreCutoverRowsAsAsiaSeoulWallClocks() {
        LocalDateTime legacyWallClock = LocalDateTime.of(2026, 7, 20, 18, 30);

        assertThat(MeetupTimePolicy.toUtcOffset(legacyWallClock, null))
                .isEqualTo(OffsetDateTime.of(2026, 7, 20, 9, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void preservesNewUtcRowsAsTheSameInstant() {
        LocalDateTime utcValue = LocalDateTime.of(2026, 7, 20, 9, 30);

        assertThat(MeetupTimePolicy.toUtcOffset(utcValue, MeetupTimeBasis.UTC))
                .isEqualTo(OffsetDateTime.of(2026, 7, 20, 9, 30, 0, 0, ZoneOffset.UTC));
    }
}
