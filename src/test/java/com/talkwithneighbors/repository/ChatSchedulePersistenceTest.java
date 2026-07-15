package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleRsvp;
import com.talkwithneighbors.entity.ChatScheduleRsvpStatus;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import com.talkwithneighbors.config.TestConfig;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({ChatRoomDeletionRepository.class, TestConfig.class})
class ChatSchedulePersistenceTest {
    @Autowired UserRepository userRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatScheduleRepository chatScheduleRepository;
    @Autowired ChatScheduleRsvpRepository chatScheduleRsvpRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomDeletionRepository deletionRepository;
    @Autowired EntityManager entityManager;

    @Test
    void oneRoomStoresMultipleDetailedSchedulesAndDeletionRemovesTheirGraph() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        User host = userRepository.save(user("host-" + suffix));
        User member = userRepository.save(user("member-" + suffix));
        ChatRoom room = new ChatRoom();
        room.setId("room-" + suffix);
        room.setName("schedule persistence");
        room.setType(ChatRoomType.GROUP);
        room.setCreator(host);
        room.setParticipants(new HashSet<>(List.of(host, member)));
        room = chatRoomRepository.saveAndFlush(room);

        ChatSchedule morning = saveSchedule(room, host, "morning-" + suffix, "아침 산책", 1);
        ChatSchedule evening = saveSchedule(room, member, "evening-" + suffix, "저녁 식사", 2);
        chatScheduleRsvpRepository.saveAndFlush(new ChatScheduleRsvp(
                morning, member, ChatScheduleRsvpStatus.ATTENDING));

        Message card = new Message();
        card.setId("card-" + suffix);
        card.setChatRoom(room);
        card.setSender(host);
        card.setContent("일정: 아침 산책");
        card.setType(Message.MessageType.SCHEDULE);
        card.setSchedule(morning);
        messageRepository.saveAndFlush(card);
        entityManager.clear();

        List<ChatSchedule> schedules = chatScheduleRepository.findDetailedByRoomId(room.getId());
        assertThat(schedules).extracting(ChatSchedule::getId)
                .containsExactly(morning.getId(), evening.getId());
        assertThat(schedules.get(0).getRsvps()).hasSize(2);
        assertThat(messageRepository.findBySchedule_IdAndChatRoom_Id(morning.getId(), room.getId()))
                .get().extracting(Message::getId).isEqualTo(card.getId());

        ChatRoomDeletionRepository.ChatRoomDeletionResult result =
                deletionRepository.deleteByRoomId(room.getId());

        assertThat(result.scheduleRsvps()).isEqualTo(3);
        assertThat(result.schedules()).isEqualTo(2);
        assertThat(chatRoomRepository.findById(room.getId())).isEmpty();
        assertThat(chatScheduleRepository.count()).isZero();
        assertThat(chatScheduleRsvpRepository.count()).isZero();
        assertThat(messageRepository.findById(card.getId())).isEmpty();
    }

    private ChatSchedule saveSchedule(
            ChatRoom room,
            User creator,
            String id,
            String title,
            int daysFromNow
    ) {
        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(id);
        schedule.setRoom(room);
        schedule.setCreator(creator);
        schedule.setTitle(title);
        schedule.setStartsAt(Instant.now().plusSeconds(daysFromNow * 86_400L));
        schedule.setDurationMinutes(90);
        schedule.setTimeZone("Asia/Seoul");
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        schedule.addRsvp(new ChatScheduleRsvp(
                schedule, creator, ChatScheduleRsvpStatus.ATTENDING));
        return chatScheduleRepository.saveAndFlush(schedule);
    }

    private User user(String username) {
        return User.builder()
                .email(username + "@example.invalid")
                .username(username)
                .password("hash")
                .latitude(37.5)
                .longitude(127.0)
                .address("Seoul")
                .build();
    }
}
