package com.talkwithneighbors.repository;

import com.talkwithneighbors.config.TestConfig;
import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.MessageAttachment;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestConfig.class)
class ChatPrivacyRepositoryTest {

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    ChatRoomRepository chatRoomRepository;

    @Autowired
    MessageRepository messageRepository;

    @Test
    void roomSearchNeverReturnsRoomsTheRequesterHasNotJoined() {
        User requester = persistUser("requester");
        User peer = persistUser("peer");
        User outsider = persistUser("outsider");
        persistRoom("requester-direct", "Coffee chat", ChatRoomType.ONE_ON_ONE,
                requester, requester, peer);
        persistRoom("outsider-direct", "Hidden coffee chat", ChatRoomType.ONE_ON_ONE,
                peer, peer, outsider);
        persistRoom("requester-group", "Neighborhood walk", ChatRoomType.GROUP,
                requester, requester, outsider);
        entityManager.flush();
        entityManager.clear();

        var allJoinedRooms = chatRoomRepository.searchParticipantRooms(
                requester, null, "", PageRequest.of(0, 20)
        );
        var matchingDirectRooms = chatRoomRepository.searchParticipantRooms(
                requester, ChatRoomType.ONE_ON_ONE, "coffee", PageRequest.of(0, 20)
        );

        assertThat(allJoinedRooms.getContent()).extracting(ChatRoom::getId)
                .containsExactlyInAnyOrder("requester-direct", "requester-group")
                .doesNotContain("outsider-direct");
        assertThat(matchingDirectRooms.getContent()).extracting(ChatRoom::getId)
                .containsExactly("requester-direct");
    }

    @Test
    void attachmentLookupRequiresRoomParticipationAndAcceptsThumbnailUrls() {
        User participant = persistUser("participant");
        User peer = persistUser("media-peer");
        User outsider = persistUser("media-outsider");
        ChatRoom room = persistRoom(
                "media-room", "Media room", ChatRoomType.ONE_ON_ONE,
                participant, participant, peer
        );
        Message message = new Message();
        message.setChatRoom(room);
        message.setSender(participant);
        message.setContent("");
        message.setType(Message.MessageType.IMAGE);
        message.getAttachments().add(new MessageAttachment(
                "/uploads/chat/photo.webp",
                "/uploads/chat/photo-thumbnail.webp",
                ChatAttachmentType.IMAGE,
                "image/webp",
                "photo.webp",
                128L,
                100,
                100,
                null
        ));
        entityManager.persist(message);
        entityManager.flush();
        entityManager.clear();

        assertThat(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/photo.webp", participant.getId()
        )).isEqualTo(1L);
        assertThat(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/photo-thumbnail.webp", participant.getId()
        )).isEqualTo(1L);
        assertThat(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/photo.webp", outsider.getId()
        )).isZero();
        assertThat(messageRepository.countAccessibleChatAttachments(
                "/uploads/chat/missing.webp", participant.getId()
        )).isZero();
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

    private ChatRoom persistRoom(
            String id,
            String name,
            ChatRoomType type,
            User creator,
            User... participants
    ) {
        ChatRoom room = new ChatRoom();
        room.setId(id);
        room.setName(name);
        room.setType(type);
        room.setCreator(creator);
        room.getParticipants().addAll(List.of(participants));
        return entityManager.persist(room);
    }
}
