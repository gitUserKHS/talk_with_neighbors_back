package com.talkwithneighbors.service;

import com.talkwithneighbors.dto.meetup.CreateHobbyMeetupRequest;
import com.talkwithneighbors.dto.meetup.HobbyMeetupDto;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class HobbyMeetupService {
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<HobbyMeetupDto> findMeetups(Long currentUserId, String keyword, String interest, Pageable pageable) {
        User currentUser = getUser(currentUserId);
        String normalizedKeyword = normalize(keyword);
        String normalizedInterest = normalize(interest);

        List<HobbyMeetupDto> meetups = chatRoomRepository
                .findByTypeAndPublicRoomTrueOrderByLastMessageTimeDesc(ChatRoomType.GROUP)
                .stream()
                .filter(room -> matches(room, normalizedKeyword, normalizedInterest))
                .map(room -> HobbyMeetupDto.fromEntity(room, currentUser))
                .sorted(Comparator
                        .comparingInt((HobbyMeetupDto meetup) -> meetup.getSharedInterests().size()).reversed()
                        .thenComparing(HobbyMeetupDto::getLastMessageTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(HobbyMeetupDto::getTitle, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        int start = Math.min((int) pageable.getOffset(), meetups.size());
        int end = Math.min(start + pageable.getPageSize(), meetups.size());
        return new PageImpl<>(meetups.subList(start, end), pageable, meetups.size());
    }

    public HobbyMeetupDto createMeetup(Long creatorId, CreateHobbyMeetupRequest request) {
        User creator = getUser(creatorId);
        List<String> tags = cleanTags(request.getInterestTags());
        if (tags.isEmpty()) {
            throw new ChatException("At least one hobby tag is required.", HttpStatus.BAD_REQUEST);
        }

        ChatRoom room = new ChatRoom();
        room.setName(request.getTitle().trim());
        room.setType(ChatRoomType.GROUP);
        room.setPublicRoom(true);
        room.setDescription(trimToNull(request.getDescription()));
        room.setInterestTags(tags);
        room.setLocation(trimToNull(request.getLocation()));
        room.setMaxParticipants(request.getMaxParticipants());
        room.setCreator(creator);
        room.getParticipants().add(creator);

        return HobbyMeetupDto.fromEntity(chatRoomRepository.save(room), creator);
    }

    public HobbyMeetupDto joinMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetup(roomId);

        if (!room.getParticipants().contains(user)) {
            if (room.getMaxParticipants() != null && room.getParticipants().size() >= room.getMaxParticipants()) {
                throw new ChatException("This hobby meetup is already full.", HttpStatus.CONFLICT);
            }
            room.getParticipants().add(user);
            chatRoomRepository.save(room);
        }
        return HobbyMeetupDto.fromEntity(room, user);
    }

    public void leaveMeetup(Long userId, String roomId) {
        User user = getUser(userId);
        ChatRoom room = getPublicMeetup(roomId);
        if (!room.getParticipants().remove(user)) {
            throw new ChatException("You are not a participant in this hobby meetup.", HttpStatus.BAD_REQUEST);
        }
        chatRoomRepository.save(room);
    }

    private boolean matches(ChatRoom room, String keyword, String interest) {
        if (!keyword.isEmpty()) {
            String searchable = String.join(" ", List.of(
                    Objects.toString(room.getName(), ""),
                    Objects.toString(room.getDescription(), ""),
                    Objects.toString(room.getLocation(), ""),
                    String.join(" ", room.getInterestTags() != null ? room.getInterestTags() : List.of())
            )).toLowerCase(Locale.ROOT);
            if (!searchable.contains(keyword)) {
                return false;
            }
        }
        return interest.isEmpty() || (room.getInterestTags() != null && room.getInterestTags().stream()
                .filter(Objects::nonNull)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .anyMatch(interest::equals));
    }

    private List<String> cleanTags(List<String> values) {
        Map<String, String> uniqueTags = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                String trimmed = trimToNull(value);
                if (trimmed != null) {
                    uniqueTags.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                }
            }
        }
        return new ArrayList<>(uniqueTags.values());
    }

    private ChatRoom getPublicMeetup(String roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND));
        if (room.getType() != ChatRoomType.GROUP || !room.isPublicRoom()) {
            throw new ChatException("Hobby meetup not found.", HttpStatus.NOT_FOUND);
        }
        return room;
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found.", HttpStatus.NOT_FOUND));
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed.toLowerCase(Locale.ROOT) : "";
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
