package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅방 참가자 ID와 표시 이름을 짧게 캐시한다.
 * 타이핑 신호는 키 입력마다 들어오므로 방마다 30초에 한 번만 MySQL을 조회하고,
 * 항목 수가 상한을 넘으면 통째로 비워 단일 노드 힙을 보호한다.
 * 참가자 변경은 최대 TTL 동안 늦게 반영되며, 그 사이의 오차는 타이핑 신호에서 허용 가능하다.
 */
@Component
public class RoomParticipantCache {
    static final Duration TTL = Duration.ofSeconds(30);
    static final int MAX_ROOMS = 500;

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public RoomParticipantCache(ChatRoomRepository chatRoomRepository, UserRepository userRepository) {
        this(chatRoomRepository, userRepository, Clock.systemUTC());
    }

    RoomParticipantCache(ChatRoomRepository chatRoomRepository, UserRepository userRepository, Clock clock) {
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * 채팅방 참가자 사용자 ID 목록. 방이 없으면 빈 목록을 돌려준다.
     */
    public List<Long> participantIds(String roomId) {
        return entry(roomId).ids();
    }

    /**
     * 캐시된 항목 안에서 참가자 표시 이름을 찾고, 없으면 한 번만 조회해 같은 항목에 담아 둔다.
     */
    public String participantName(String roomId, Long userId) {
        return entry(roomId).names().computeIfAbsent(userId, id ->
                userRepository.findById(id).map(User::getUsername).orElse(""));
    }

    public void evict(String roomId) {
        entries.remove(roomId);
    }

    private Entry entry(String roomId) {
        Instant now = clock.instant();
        Entry cached = entries.get(roomId);
        if (cached != null && !cached.isExpired(now)) {
            return cached;
        }
        List<Long> ids = List.copyOf(chatRoomRepository.findParticipantIds(roomId));
        if (entries.size() >= MAX_ROOMS) {
            entries.clear();
        }
        Entry loaded = new Entry(ids, new ConcurrentHashMap<>(), now);
        entries.put(roomId, loaded);
        return loaded;
    }

    private record Entry(List<Long> ids, Map<Long, String> names, Instant loadedAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(loadedAt.plus(TTL));
        }
    }
}
