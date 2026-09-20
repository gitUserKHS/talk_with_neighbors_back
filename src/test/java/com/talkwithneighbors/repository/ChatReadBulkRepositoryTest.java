package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestConfig.class)
class ChatReadBulkRepositoryTest {

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    MessageRepository messageRepository;

    @Test
    void bulkReadMarkingInsertsOnlyMissingRowsAndIsIdempotent() {
        User reader = persistUser("reader");
        User peer = persistUser("peer");
        ChatRoom room = persistRoom("bulk-read-room", reader, reader, peer);
        Message own = persistMessage("own-message", room, reader);
        Message alreadyRead = persistMessage("already-read", room, peer);
        alreadyRead.getReadByUsers().add(reader.getId());
        Message unread = persistMessage("unread", room, peer);
        Message deleted = persistMessage("deleted", room, peer);
        deleted.setDeleted(true);
        entityManager.flush();
        entityManager.clear();

        int firstAffected = messageRepository.markAllVisibleAsRead(room.getId(), reader.getId());
        int secondAffected = messageRepository.markAllVisibleAsRead(room.getId(), reader.getId());
        entityManager.clear();

        assertThat(firstAffected).isEqualTo(1);
        assertThat(secondAffected).isZero();
        assertThat(entityManager.find(Message.class, unread.getId()).getReadByUsers())
                .containsExactlyInAnyOrder(peer.getId(), reader.getId());
        assertThat(entityManager.find(Message.class, alreadyRead.getId()).getReadByUsers())
                .containsExactlyInAnyOrder(peer.getId(), reader.getId());
        assertThat(entityManager.find(Message.class, own.getId()).getReadByUsers())
                .containsExactly(reader.getId());
        assertThat(entityManager.find(Message.class, deleted.getId()).getReadByUsers())
                .containsExactly(peer.getId());
        assertThat(messageRepository.countUnreadMessages(room.getId(), reader.getId()))
                .isEqualTo(1L);
    }

    private User persistUser(String username) {
        User user = new User();
        user.setEmail(username + "@example.test");
        user.setUsername(username);
        user.setPassword("encoded-password");
        user.setLatitude(37.5);
        user.setLongitude(127.0);
        user.setAddress("Seoul");
        return entityManager.persist(user);
    }

    private ChatRoom persistRoom(String id, User creator, User... participants) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName("Room " + id);
        room.setType(ChatRoomType.GROUP);
        room.setCreator(creator);
        room.getParticipants().addAll(List.of(participants));
        return entityManager.persist(room);
    }

    private Message persistMessage(String id, ChatRoom room, User sender) {
        Message message = new Message();
        message.setId(id);
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent("hello");
        message.setType(Message.MessageType.TEXT);
        message.getReadByUsers().add(sender.getId());
        return entityManager.persist(message);
    }
}
