package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomParticipantCacheTest {
    private static final Instant START = Instant.parse("2026-09-20T09:00:00Z");

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    private final MutableClock clock = new MutableClock(START);

    @Test
    void participantIdsAndNamesHitTheDatabaseOnceWithinTheTtl() {
        RoomParticipantCache cache = new RoomParticipantCache(chatRoomRepository, userRepository, clock);
        when(chatRoomRepository.findParticipantIds("room-1")).thenReturn(List.of(1L, 2L));
        User alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));

        cache.participantIds("room-1");
        clock.advanceSeconds(29);
        assertEquals(List.of(1L, 2L), cache.participantIds("room-1"));
        assertEquals("alice", cache.participantName("room-1", 1L));
        assertEquals("alice", cache.participantName("room-1", 1L));

        verify(chatRoomRepository, times(1)).findParticipantIds("room-1");
        verify(userRepository, times(1)).findById(1L);
    }

    @Test
    void expiredEntryIsReloadedFromTheDatabase() {
        RoomParticipantCache cache = new RoomParticipantCache(chatRoomRepository, userRepository, clock);
        when(chatRoomRepository.findParticipantIds("room-1"))
                .thenReturn(List.of(1L, 2L))
                .thenReturn(List.of(1L, 2L, 3L));

        cache.participantIds("room-1");
        clock.advanceSeconds(30);

        assertEquals(List.of(1L, 2L, 3L), cache.participantIds("room-1"));
        verify(chatRoomRepository, times(2)).findParticipantIds("room-1");
    }

    @Test
    void cacheIsClearedInsteadOfGrowingPastTheRoomCap() {
        RoomParticipantCache cache = new RoomParticipantCache(chatRoomRepository, userRepository, clock);
        when(chatRoomRepository.findParticipantIds(anyString())).thenReturn(List.of(1L));

        for (int index = 0; index < RoomParticipantCache.MAX_ROOMS; index++) {
            cache.participantIds("room-" + index);
        }
        cache.participantIds("room-overflow");
        cache.participantIds("room-0");

        verify(chatRoomRepository, times(2)).findParticipantIds("room-0");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
