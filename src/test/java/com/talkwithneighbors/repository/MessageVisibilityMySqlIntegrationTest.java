package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatSchedule;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Tag("mysql")
@ActiveProfiles("mysql-it")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestConfig.class)
class MessageVisibilityMySqlIntegrationTest {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatScheduleRepository chatScheduleRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void scheduleCardsStayOutOfPaginationUnreadCountsAndVisiblePreviewQueries() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User host = userRepository.saveAndFlush(user("visibility-host-" + suffix));
        User member = userRepository.saveAndFlush(user("visibility-member-" + suffix));
        ChatRoom room = new ChatRoom();
        room.setId("visibility-room-" + suffix);
        room.setName("Visibility query room");
        room.setType(ChatRoomType.GROUP);
        room.setCreator(host);
        room.setParticipants(new HashSet<>(List.of(host, member)));
        room = chatRoomRepository.saveAndFlush(room);

        LocalDateTime base = LocalDateTime.of(2099, 8, 1, 10, 0);
        Message unreadText = message("unread-" + suffix, room, host,
                Message.MessageType.TEXT, "unread", base.plusMinutes(1));
        Message firstCard = scheduleCard("first-card-" + suffix, room, host,
                "first-schedule-" + suffix, base.plusMinutes(2));
        Message readText = message("read-" + suffix, room, host,
                Message.MessageType.TEXT, "read", base.plusMinutes(3));
        readText.getReadByUsers().add(member.getId());
        Message deletedText = message("deleted-" + suffix, room, host,
                Message.MessageType.SYSTEM, "", base.plusMinutes(4));
        deletedText.setDeleted(true);
        Message secondCard = scheduleCard("second-card-" + suffix, room, host,
                "second-schedule-" + suffix, base.plusMinutes(5));
        messageRepository.saveAllAndFlush(List.of(
                unreadText, firstCard, readText, deletedText, secondCard));
        room.setLastMessage("Schedule: hidden card");
        room.setLastMessageTime(secondCard.getCreatedAt());
        chatRoomRepository.saveAndFlush(room);

        Page<Message> firstPage = messageRepository
                .findVisibleByChatRoomIdOrderByCreatedAtDesc(
                        room.getId(), Message.MessageType.SCHEDULE, PageRequest.of(0, 2));
        Page<Message> secondPage = messageRepository
                .findVisibleByChatRoomIdOrderByCreatedAtDesc(
                        room.getId(), Message.MessageType.SCHEDULE, PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(Message::getId)
                .containsExactly(deletedText.getId(), readText.getId());
        assertThat(secondPage.getContent()).extracting(Message::getId)
                .containsExactly(unreadText.getId());
        assertThat(firstPage.getContent()).allMatch(message ->
                message.getType() != Message.MessageType.SCHEDULE);

        assertThat(messageRepository.findVisibleActiveByChatRoomIdOrderByCreatedAtDesc(
                room.getId(), Message.MessageType.SCHEDULE, PageRequest.of(0, 10)))
                .extracting(Message::getId)
                .containsExactly(readText.getId(), unreadText.getId());
        assertThat(messageRepository.findVisibleUnreadMessages(
                room.getId(), member.getId(), Message.MessageType.SCHEDULE))
                .extracting(Message::getId)
                .containsExactly(unreadText.getId());
        assertThat(messageRepository.countVisibleUnreadMessages(
                room.getId(), member.getId(), Message.MessageType.SCHEDULE))
                .isEqualTo(1);
        assertThat(messageRepository.findParticipantRoomIdsWithSchedulePreview(
                member, Message.MessageType.SCHEDULE))
                .containsExactly(room.getId());
    }

    private Message scheduleCard(
            String messageId,
            ChatRoom room,
            User sender,
            String scheduleId,
            LocalDateTime createdAt
    ) {
        ChatSchedule schedule = new ChatSchedule();
        schedule.setId(scheduleId);
        schedule.setRoom(room);
        schedule.setCreator(sender);
        schedule.setTitle("Hidden calendar card");
        schedule.setStartsAt(Instant.parse("2099-08-10T10:00:00Z")
                .plusSeconds(createdAt.getMinute()));
        schedule.setDurationMinutes(60);
        schedule.setTimeZone("Asia/Seoul");
        schedule.setStatus(ChatScheduleStatus.SCHEDULED);
        chatScheduleRepository.saveAndFlush(schedule);
        Message card = message(messageId, room, sender,
                Message.MessageType.SCHEDULE, "Schedule card", createdAt);
        card.setSchedule(schedule);
        return card;
    }

    private Message message(
            String id,
            ChatRoom room,
            User sender,
            Message.MessageType type,
            String content,
            LocalDateTime createdAt
    ) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setType(type);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }

    private User user(String username) {
        return User.builder()
                .email(username + "@example.invalid")
                .username(username)
                .password("test-password-hash")
                .latitude(37.5)
                .longitude(127.0)
                .address("Seoul")
                .build();
    }
}
