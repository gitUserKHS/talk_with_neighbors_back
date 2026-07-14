package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.MeetupTimeBasis;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Centralizes the compatibility contract for persisted meetup wall clocks. */
public final class MeetupTimePolicy {
    public static final ZoneId LEGACY_ZONE = ZoneId.of("Asia/Seoul");

    private MeetupTimePolicy() {
    }

    public static OffsetDateTime toUtcOffset(LocalDateTime storedValue, MeetupTimeBasis basis) {
        Instant instant = toInstant(storedValue, basis);
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    public static Instant toInstant(LocalDateTime storedValue, MeetupTimeBasis basis) {
        if (storedValue == null) {
            return null;
        }
        if (basis == MeetupTimeBasis.UTC) {
            return storedValue.toInstant(ZoneOffset.UTC);
        }
        return storedValue.atZone(LEGACY_ZONE).toInstant();
    }

    public static boolean isPast(LocalDateTime storedValue, MeetupTimeBasis basis, Instant now) {
        Instant value = toInstant(storedValue, basis);
        return value != null && value.isBefore(now);
    }
}
