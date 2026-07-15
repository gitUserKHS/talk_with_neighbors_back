package com.talkwithneighbors.service.impl;

import com.talkwithneighbors.dto.ChatMessageDto;
import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.UpdateChatRoomRequest;
import com.talkwithneighbors.domain.event.ChatMessageCommittedEvent;
import com.talkwithneighbors.domain.event.ChatMessageChangedEvent;
import com.talkwithneighbors.domain.event.ChatRoomDeletedEvent;
import com.talkwithneighbors.domain.event.MediaFilesDeletedEvent;
import com.talkwithneighbors.domain.event.MeetupJoinedEvent;
import com.talkwithneighbors.entity.ChatRoom;
import com.talkwithneighbors.entity.ChatRoomType;
import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.Message;
import com.talkwithneighbors.entity.MessageAttachment;
import com.talkwithneighbors.entity.Message.MessageType;
import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.UserAccountType;
import com.talkwithneighbors.exception.ChatException;
import com.talkwithneighbors.repository.ChatRoomRepository;
import com.talkwithneighbors.repository.ChatRoomDeletionRepository;
import com.talkwithneighbors.repository.ChatScheduleRepository;
import com.talkwithneighbors.repository.ChatScheduleRsvpRepository;
import com.talkwithneighbors.entity.ChatScheduleStatus;
import com.talkwithneighbors.repository.MessageRepository;
import com.talkwithneighbors.repository.MeetupWaitlistRepository;
import com.talkwithneighbors.repository.UserRepository;
import com.talkwithneighbors.repository.UserBlockRepository;
import com.talkwithneighbors.service.ChatService;
import com.talkwithneighbors.service.MeetupTimePolicy;
import com.talkwithneighbors.service.NotificationService;
import com.talkwithneighbors.outbox.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final UserBlockRepository userBlockRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ChatRoomDeletionRepository chatRoomDeletionRepository;
    private final ChatScheduleRepository chatScheduleRepository;
    private final ChatScheduleRsvpRepository chatScheduleRsvpRepository;
    private final MeetupWaitlistRepository meetupWaitlistRepository;
    private final DomainEventPublisher domainEventPublisher;

    @Override
    @Transactional
    public ChatRoomDto createRoom(String name, ChatRoomType type, String creatorIdString, List<String> participantNicknames) {
        Long creatorId = Long.parseLong(creatorIdString);
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ChatException("Creator not found with id: " + creatorId, HttpStatus.NOT_FOUND));

        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setType(type);
        chatRoom.setCreator(creator);
        chatRoom.getParticipants().add(creator); // 생성자는 항상 참여

        List<User> participantsFound = new ArrayList<>();
        if (participantNicknames != null && !participantNicknames.isEmpty()) {
            // 자기 자신(생성자)의 닉네임은 제외하고, 중복된 닉네임도 제거
            List<String> distinctOtherUsernames = participantNicknames.stream()
                                                              .filter(username -> !username.equalsIgnoreCase(creator.getUsername()))
                                                              .distinct()
                                                              .collect(Collectors.toList());
            if (!distinctOtherUsernames.isEmpty()) {
                participantsFound = userRepository.findAllByUsernameIn(distinctOtherUsernames);
                if (participantsFound.size() != distinctOtherUsernames.size()) {
                    // 요청한 닉네임 중 일부를 찾지 못한 경우
                    List<String> foundUsernames = participantsFound.stream().map(User::getUsername).collect(Collectors.toList());
                    List<String> notFoundUsernames = distinctOtherUsernames.stream()
                                                                          .filter(reqName -> foundUsernames.stream().noneMatch(foundName -> foundName.equalsIgnoreCase(reqName)))
                                                                          .collect(Collectors.toList());
                    log.warn(
                            "Could not find all requested chat participants. requestedCount={}, foundCount={}, notFoundCount={}",
                            distinctOtherUsernames.size(),
                            foundUsernames.size(),
                            notFoundUsernames.size()
                    );
                    // 정책: 찾지 못한 사용자가 있으면 채팅방 생성 실패 처리
                    throw new ChatException("Could not find user(s): " + String.join(", ", notFoundUsernames) + ". Please check the usernames.", HttpStatus.BAD_REQUEST);
                }
            }
        }

        if (type == ChatRoomType.ONE_ON_ONE) {
            // 1:1 채팅은 생성자 외 정확히 1명의 다른 참여자가 필요
            if (participantsFound.size() != 1) {
                throw new ChatException("ONE_ON_ONE chat requires exactly one other participant (excluding yourself). Found " + participantsFound.size() + " other participants.", HttpStatus.BAD_REQUEST);
            }
            
            User otherParticipant = participantsFound.get(0);
            // 생성자와 다른 참여자가 동일 인물인지 한 번 더 확인 (닉네임 대소문자 등으로 필터링 우회 가능성 방지)
            if (otherParticipant.getId().equals(creator.getId())) {
                 throw new ChatException("ONE_ON_ONE chat cannot be created with oneself as the only other participant.", HttpStatus.BAD_REQUEST);
            }
            requireNotBlocked(creator.getId(), otherParticipant.getId());
            
            chatRoom.getParticipants().add(otherParticipant); // 다른 참여자 추가
            
            // 1:1 채팅방 이름: "유저명1, 유저명2" (참여자는 이미 2명으로 확정됨)
            List<String> chatParticipantNames = chatRoom.getParticipants().stream()
                                                        .map(User::getUsername)
                                                        .sorted(String::compareToIgnoreCase)
                                                        .collect(Collectors.toList());
            chatRoom.setName(String.join(", ", chatParticipantNames));

        } else { // GROUP chat
            if (name == null || name.trim().isEmpty()) {
                // 그룹 채팅은 이름이 필수 (변경 가능: 이름 없으면 참여자 기반 자동생성 등)
                throw new ChatException("Group chat name cannot be empty.", HttpStatus.BAD_REQUEST);
            }
            chatRoom.setName(name);
            if (!participantsFound.isEmpty()) { // 조회된 참여자가 있다면 추가
                participantsFound.forEach(p -> {
                    if (!chatRoom.getParticipants().contains(p)) { // 중복 추가 방지
                        chatRoom.getParticipants().add(p);
                    }
                });
            }
            // 그룹 채팅 최소/최대 인원 제한 등 추가 정책이 있다면 여기서 검증
        }
        
        ChatRoom savedChatRoom = chatRoomRepository.save(chatRoom);
        log.info("Chat room created: ID={}, Name='{}', Type={}, Creator={}, ParticipantsCount={}", 
                 savedChatRoom.getId(), savedChatRoom.getName(), savedChatRoom.getType(), 
                 savedChatRoom.getCreator().getUsername(), savedChatRoom.getParticipants().size());
        
        return ChatRoomDto.fromEntity(savedChatRoom, creator, messageRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> getChatRoomsForUser(String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        // 최근 활동 순으로 정렬된 채팅방 목록 조회 (카카오톡과 같은 방식)
        Page<ChatRoom> roomsPage = chatRoomRepository.findByParticipantsContainingOrderByLastMessageTimeDesc(user, pageable);
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, user, messageRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRoomDto getRoomById(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new ChatException("User not found with id: " + userId, HttpStatus.NOT_FOUND));
        ChatRoom chatRoom = chatRoomRepository.findByIdAndParticipantsContaining(roomId, currentUser)
                .orElseThrow(() -> new ChatException(
                        "Chat room not found or user not a participant", HttpStatus.NOT_FOUND));
        return ChatRoomDto.fromEntity(chatRoom, currentUser, messageRepository);
    }

    @Override
    @Transactional
    public void joinRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));

        if (chatRoom.getType() != ChatRoomType.GROUP || !chatRoom.isPublicRoom()) {
            throw new ChatException("Only public hobby meetups can be joined directly.", HttpStatus.FORBIDDEN);
        }
        if (chatRoom.getCreator() != null
                && userBlockRepository.existsBetween(userId, chatRoom.getCreator().getId())) {
            throw new ChatException("차단 관계인 사용자의 모임에는 참여할 수 없어.", HttpStatus.FORBIDDEN);
        }
        if (MeetupTimePolicy.isPast(
                chatRoom.getRegistrationDeadline(), chatRoom.getMeetupTimeBasis(), Instant.now())) {
            throw new ChatException("모임 신청이 마감되었어.", HttpStatus.CONFLICT);
        }

        if (chatRoom.getParticipants().contains(user)) {
            log.info("User {} is already a participant in room {}", user.getId(), roomId);
            return;
        }
        if (meetupWaitlistRepository.countByRoom_Id(roomId) > 0) {
            throw new ChatException(
                    "대기 순서를 지키기 위해 모임 참여 화면에서 신청해 줘.",
                    HttpStatus.CONFLICT);
        }
        if (chatRoom.getMaxParticipants() != null
                && chatRoom.getParticipants().size() >= chatRoom.getMaxParticipants()) {
            throw new ChatException("This hobby meetup is already full.", HttpStatus.CONFLICT);
        }
        chatRoom.getParticipants().add(user);
        chatRoomRepository.save(chatRoom);
        if (chatRoom.getCreator() == null
                || chatRoom.getCreator().getAccountType() != UserAccountType.SYSTEM) {
            domainEventPublisher.publish(MeetupJoinedEvent.create(
                    chatRoom.getId(),
                    chatRoom.getName(),
                    user.getId(),
                    chatRoom.getCreator() != null ? chatRoom.getCreator().getId() : null));
        }
        log.info("User {} successfully joined room {}", user.getId(), roomId);
    }

    @Override
    @Transactional
    public void leaveRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found"));
        if (chatScheduleRepository.existsByRoom_IdAndCreator_IdAndStatusAndStartsAtAfter(
                roomId, userId, ChatScheduleStatus.SCHEDULED, java.time.Instant.now())) {
            throw new ChatException(
                    "먼저 이 채팅방에서 만든 예정 일정을 취소해 줘.",
                    HttpStatus.CONFLICT);
        }
        if (chatRoom.getParticipants().remove(user)) {
            chatScheduleRsvpRepository.deleteBySchedule_Room_IdAndUser_Id(roomId, userId);
            chatRoomRepository.save(chatRoom);
            log.info("User {} left room {}", user.getId(), roomId);
        } else {
            log.warn("User {} was not a participant in room {}. No action taken.", user.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public MessageDto sendMessage(String roomId, Long senderId, String content) {
        return sendMessage(roomId, senderId, content, List.of());
    }

    @Override
    @Transactional
    public MessageDto sendMessage(
            String roomId,
            Long senderId,
            String content,
            List<MessageAttachment> attachments
    ) {
        log.debug("[SendMessage] Attempting to send message. RoomId: {}, SenderId: {}", roomId, senderId);

        List<MessageAttachment> safeAttachments = attachments == null ? List.of() : List.copyOf(attachments);
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isEmpty() && safeAttachments.isEmpty()) {
            throw new ChatException("메시지 내용 또는 첨부 파일이 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        if (normalizedContent.length() > 2000) {
            throw new ChatException("메시지는 2,000자까지 입력할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        if (safeAttachments.size() > 5) {
            throw new ChatException("첨부 파일은 메시지당 최대 5개입니다.", HttpStatus.BAD_REQUEST);
        }

        // Serialize updates to the shared latest-message row for this room.
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> {
                    log.error("[SendMessage] Chat room not found with id: {}", roomId);
                    return new ChatException("Chat room not found: " + roomId, HttpStatus.NOT_FOUND);
                });
        log.debug("[SendMessage] Found chat room: ID={}", room.getId());

        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> {
                    log.error("[SendMessage] Sender not found with id: {}", senderId);
                    return new ChatException("User not found: " + senderId, HttpStatus.NOT_FOUND);
                });
        log.debug("[SendMessage] Found sender: ID={}", sender.getId());
        
        // 참여 중인 사용자만 메시지를 보낼 수 있습니다.
        if (room.getParticipants().stream().noneMatch(p -> p.getId().equals(senderId))) {
            log.warn("[SendMessage] Sender (ID: {}) is not a participant in room (ID: {}).", senderId, roomId);
            throw new ChatException("Sender is not a participant of this chat room.", HttpStatus.FORBIDDEN);
        }
        if (room.getType() == ChatRoomType.ONE_ON_ONE) {
            room.getParticipants().stream()
                    .filter(participant -> !participant.getId().equals(senderId))
                    .findFirst()
                    .ifPresent(participant -> requireNotBlocked(senderId, participant.getId()));
        }

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setChatRoom(room);
        message.setSender(sender);
        message.setContent(normalizedContent);
        message.setAttachments(new ArrayList<>(safeAttachments));
        message.setType(resolveMessageType(normalizedContent, safeAttachments));
        message.setCreatedAt(LocalDateTime.now());
        message.getReadByUsers().add(sender.getId()); // 발신자는 항상 읽음 처리
        log.debug("[SendMessage] Prepared message object: ID={}, ChatRoomID={}, SenderID={}, CreatedAt={}",
                 message.getId(), message.getChatRoom().getId(), message.getSender().getId(), message.getCreatedAt());

        Message savedMessage;
        try {
            savedMessage = messageRepository.save(message);
            log.debug("[SendMessage] Message saved to DB: ID={}", savedMessage.getId());
        } catch (Exception e) {
            log.error("[SendMessage] Failed to save message to DB. RoomId: {}, SenderId: {}", roomId, senderId, e);
            throw new ChatException("Failed to save message.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        try {
            room.setLastMessage(lastMessagePreview(savedMessage));
            room.setLastMessageTime(savedMessage.getCreatedAt());
            chatRoomRepository.save(room);
            log.debug("[SendMessage] Updated chat room's last-message metadata: RoomID={}", room.getId());
        } catch (Exception e) {
            log.error("[SendMessage] Failed to update chat room's last message. RoomID: {}", room.getId(), e);
            // 이 오류는 메시지 전송 자체를 실패시키지는 않음 (이미 메시지는 저장됨)
        }

        MessageDto messageDto = MessageDto.fromEntity(savedMessage, sender.getId());
        log.debug("[SendMessage] Created MessageDto: ID={}, SenderId={}", messageDto.getId(), messageDto.getSenderId());

        // Delivery happens only after the database transaction commits.
        applicationEventPublisher.publishEvent(
                new ChatMessageCommittedEvent(
                        messageDto,
                        room.getId(),
                        senderId,
                        room.getParticipants().stream().map(User::getId).toList()));

        return messageDto;
    }

    @Override
    @Transactional
    public MessageDto updateMessage(String roomId, String messageId, Long requesterId, String content) {
        Message message = requireOwnedMessage(roomId, messageId, requesterId);
        if (message.isDeleted()) {
            throw new ChatException("삭제된 메시지는 수정할 수 없어.", HttpStatus.CONFLICT);
        }
        requireUserGeneratedMessage(message);

        String normalizedContent = content == null ? "" : content.trim();
        List<MessageAttachment> attachments = message.getAttachments() == null
                ? List.of() : message.getAttachments();
        if (normalizedContent.isEmpty() && attachments.isEmpty()) {
            throw new ChatException("메시지 내용을 비워둘 수 없어.", HttpStatus.BAD_REQUEST);
        }
        if (normalizedContent.length() > 2000) {
            throw new ChatException("메시지는 2,000자까지 수정할 수 있어.", HttpStatus.BAD_REQUEST);
        }
        if (normalizedContent.equals(message.getContent())) {
            return MessageDto.fromEntity(message, requesterId);
        }

        LocalDateTime changedAt = LocalDateTime.now();
        message.setContent(normalizedContent);
        message.setType(resolveMessageType(normalizedContent, attachments));
        message.setEditedAt(changedAt);
        message.setUpdatedAt(changedAt);
        Message savedMessage = messageRepository.save(message);
        Message latestMessage = refreshRoomLastMessage(savedMessage.getChatRoom());

        MessageDto messageDto = MessageDto.fromEntity(savedMessage, requesterId);
        publishChangedMessage(messageDto, savedMessage.getChatRoom(), latestMessage);
        return messageDto;
    }

    @Override
    @Transactional
    public MessageDto deleteMessage(String roomId, String messageId, Long requesterId) {
        Message message = requireOwnedMessage(roomId, messageId, requesterId);
        if (message.isDeleted()) {
            return MessageDto.fromEntity(message, requesterId);
        }
        requireUserGeneratedMessage(message);

        List<MessageAttachment> attachments = message.getAttachments() == null
                ? List.of() : new ArrayList<>(message.getAttachments());
        List<String> mediaUrls = attachments.stream()
                .flatMap(attachment -> Stream.of(attachment.getUrl(), attachment.getThumbnailUrl()))
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        LocalDateTime changedAt = LocalDateTime.now();
        message.setContent("");
        if (message.getAttachments() != null) {
            message.getAttachments().clear();
        }
        message.setType(MessageType.SYSTEM);
        message.setDeleted(true);
        message.setDeletedAt(changedAt);
        message.setUpdatedAt(changedAt);
        Message savedMessage = messageRepository.save(message);
        Message latestMessage = refreshRoomLastMessage(savedMessage.getChatRoom());

        MessageDto messageDto = MessageDto.fromEntity(savedMessage, requesterId);
        publishChangedMessage(messageDto, savedMessage.getChatRoom(), latestMessage);
        if (!mediaUrls.isEmpty()) {
            domainEventPublisher.publish(MediaFilesDeletedEvent.create(
                    "Message", messageId, mediaUrls));
        }
        return messageDto;
    }

    private ChatRoom requireParticipant(String roomId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatException(
                        "User not found with id: " + userId, HttpStatus.NOT_FOUND));
        return chatRoomRepository.findByIdAndParticipantsContaining(roomId, user)
                .orElseThrow(() -> new ChatException(
                        "Chat room not found or user not a participant", HttpStatus.NOT_FOUND));
    }

    private Message requireAccessibleMessage(String roomId, String messageId, Long userId) {
        requireParticipant(roomId, userId);
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ChatException("Message not found: " + messageId, HttpStatus.NOT_FOUND));
        if (message.getChatRoom() == null || !roomId.equals(message.getChatRoom().getId())) {
            throw new ChatException("Message not found: " + messageId, HttpStatus.NOT_FOUND);
        }
        return message;
    }

    private Message requireOwnedMessage(String roomId, String messageId, Long requesterId) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ChatException("채팅방을 찾을 수 없어.", HttpStatus.NOT_FOUND));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ChatException("메시지를 찾을 수 없어.", HttpStatus.NOT_FOUND));
        if (message.getChatRoom() == null || !roomId.equals(message.getChatRoom().getId())) {
            throw new ChatException("메시지를 찾을 수 없어.", HttpStatus.NOT_FOUND);
        }
        boolean isParticipant = room.getParticipants().stream()
                .anyMatch(participant -> participant.getId().equals(requesterId));
        if (!isParticipant) {
            throw new ChatException("이 채팅방의 메시지를 변경할 권한이 없어.", HttpStatus.FORBIDDEN);
        }
        if (message.getSender() == null || !message.getSender().getId().equals(requesterId)) {
            throw new ChatException("내가 보낸 메시지만 수정하거나 삭제할 수 있어.", HttpStatus.FORBIDDEN);
        }
        return message;
    }

    private void requireUserGeneratedMessage(Message message) {
        if (message.getType() != MessageType.TEXT
                && message.getType() != MessageType.IMAGE
                && message.getType() != MessageType.VIDEO
                && message.getType() != MessageType.FILE) {
            throw new ChatException("시스템 메시지는 수정하거나 삭제할 수 없어.", HttpStatus.BAD_REQUEST);
        }
    }

    private Message refreshRoomLastMessage(ChatRoom room) {
        List<Message> latestMessages = messageRepository.findActiveByChatRoomIdOrderByCreatedAtDesc(
                room.getId(), PageRequest.of(0, 1));
        Message latestMessage = null;
        if (latestMessages == null || latestMessages.isEmpty()) {
            room.setLastMessage(null);
            room.setLastMessageTime(null);
        } else {
            latestMessage = latestMessages.get(0);
            room.setLastMessage(lastMessagePreview(latestMessage));
            room.setLastMessageTime(latestMessage.getCreatedAt());
        }
        chatRoomRepository.save(room);
        return latestMessage;
    }

    private void publishChangedMessage(MessageDto messageDto, ChatRoom room, Message latestMessage) {
        applicationEventPublisher.publishEvent(new ChatMessageChangedEvent(
                messageDto,
                room.getId(),
                room.getLastMessage(),
                room.getLastMessageTime() == null ? null : room.getLastMessageTime().toString(),
                latestMessage == null || latestMessage.getSender() == null
                        ? null : latestMessage.getSender().getUsername(),
                room.getParticipants().stream().map(User::getId).toList()));
    }

    private MessageType resolveMessageType(String content, List<MessageAttachment> attachments) {
        if (content != null && !content.isBlank()) {
            return MessageType.TEXT;
        }
        ChatAttachmentType type = attachments.get(0).getType();
        return switch (type) {
            case IMAGE -> MessageType.IMAGE;
            case VIDEO -> MessageType.VIDEO;
            case FILE -> MessageType.FILE;
        };
    }

    private String lastMessagePreview(Message message) {
        if (message.getContent() != null && !message.getContent().isBlank()) {
            return message.getContent();
        }
        List<MessageAttachment> attachments = message.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return "새 메시지";
        }
        if (attachments.size() > 1) {
            return "첨부 파일 " + attachments.size() + "개";
        }
        return switch (attachments.get(0).getType()) {
            case IMAGE -> "사진";
            case VIDEO -> "동영상";
            case FILE -> "파일: " + attachments.get(0).getOriginalName();
        };
    }

    @Override
    @Transactional
    public Page<MessageDto> getMessagesByRoomId(String roomId, String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ChatException("User not found with id: " + userId, HttpStatus.NOT_FOUND));

        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ChatException("Chat room not found with id: " + roomId, HttpStatus.NOT_FOUND));

        boolean isParticipant = chatRoom.getParticipants().stream()
                .anyMatch(participant -> participant.getId().equals(user.getId()));

        if (!isParticipant) {
            throw new ChatException("Access denied to chat room " + roomId, HttpStatus.FORBIDDEN);
        }

        Page<Message> messagesPage = messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId, pageable);
        
        List<Message> messagesToUpdate = new java.util.ArrayList<>();
        List<String> readMessageIds = new java.util.ArrayList<>();
        
        messagesPage.getContent().forEach(msg -> {
            if (!msg.getReadByUsers().contains(user.getId())) {
                msg.getReadByUsers().add(user.getId());
                messagesToUpdate.add(msg);
                readMessageIds.add(msg.getId());
            }
        });
        
        if (!messagesToUpdate.isEmpty()) {
            try {
                messageRepository.saveAll(messagesToUpdate);
                log.info("[GetMessages] Marked {} messages as read for user {} in room {}", 
                         messagesToUpdate.size(), user.getId(), roomId);
                
                // 읽음 상태 변경 알림 전송
                for (String messageId : readMessageIds) {
                    try {
                        notificationService.sendMessageReadStatusUpdate(messageId, roomId, user.getId());
                    } catch (Exception e) {
                        log.error("[GetMessages] Failed to send read status update for messageId {}: {}", 
                                  messageId, e.getMessage(), e);
                    }
                }
                
            } catch (Exception e) {
                log.error("[GetMessages] Failed to save read status updates for user {} in room {}: {}", 
                          user.getId(), roomId, e.getMessage(), e);
            }
        }

        return messagesPage.map(msg -> MessageDto.fromEntity(msg, user.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> searchRooms(String query, ChatRoomType type, String userIdString, Pageable pageable) {
        Long userId = Long.parseLong(userIdString);
        User currentUser = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        
        String trimmedQuery = (query != null) ? query.trim() : "";
        Page<ChatRoom> roomsPage = chatRoomRepository.searchParticipantRooms(
                currentUser, type, trimmedQuery, pageable);
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, currentUser, messageRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatRoomDto> getAllRooms(Pageable pageable) {
        Page<ChatRoom> roomsPage = chatRoomRepository.findAll(pageable);
        return roomsPage.map(room -> ChatRoomDto.fromEntity(room, null, messageRepository)); 
    }

    @Override
    @Transactional
    public void deleteRoom(String roomId, Long requesterId) {
        log.info("[deleteRoom] Starting deletion process for roomId: {}", roomId);

        try {
            ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(roomId)
                    .orElseThrow(() -> new ChatException(
                            "Chat room not found: " + roomId, HttpStatus.NOT_FOUND));
            if (chatRoom.getCreator() == null
                    || !java.util.Objects.equals(chatRoom.getCreator().getId(), requesterId)) {
                throw new ChatException("채팅방을 만든 사람만 삭제할 수 있어.", HttpStatus.FORBIDDEN);
            }

            List<Long> participantIds = chatRoom.getParticipants().stream()
                    .map(User::getId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            List<String> attachmentUrls =
                    chatRoomDeletionRepository.findMediaUrlsByRoomId(roomId);

            ChatRoomDeletionRepository.ChatRoomDeletionResult result =
                    chatRoomDeletionRepository.deleteByRoomId(roomId);
            if (result.rooms() != 1) {
                throw new IllegalStateException(
                        "Expected to delete one chat room but deleted " + result.rooms());
            }

            if (!attachmentUrls.isEmpty()) {
                domainEventPublisher.publish(MediaFilesDeletedEvent.create(
                        "ChatRoom", roomId, attachmentUrls));
            }
            domainEventPublisher.publish(
                    ChatRoomDeletedEvent.create(roomId, participantIds));

            log.info(
                "[deleteRoom] Deleted room graph. roomId={}, messages={}, attachments={}, "
                            + "readStatuses={}, participants={}, waitlistEntries={}, "
                            + "schedules={}, scheduleRsvps={}",
                    roomId,
                    result.messages(),
                    result.attachments(),
                    result.readStatuses(),
                    result.participants(),
                    result.waitlistEntries(),
                    result.schedules(),
                    result.scheduleRsvps()
            );
        } catch (ChatException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[deleteRoom] Failed to delete roomId={}", roomId, exception);
            throw new ChatException(
                    "Failed to delete chat room.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional
    public void markMessageAsRead(String roomId, String messageId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        Message message = requireAccessibleMessage(roomId, messageId, userId);

        if (message.getReadByUsers() == null) {
            message.setReadByUsers(new HashSet<>());
        }

        if (!message.getReadByUsers().contains(userId)) {
            message.getReadByUsers().add(userId);
            messageRepository.save(message);
            log.debug("Marked message {} as read for user {}", messageId, userId);

            // 읽음 상태 변경 알림 전송
            try {
                notificationService.sendMessageReadStatusUpdate(messageId, roomId, userId);
            } catch (Exception e) {
                log.error("Failed to send read status update for messageId {}: {}", messageId, e.getMessage(), e);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        requireParticipant(roomId, userId);
        return messageRepository.countUnreadMessages(roomId, userId);
    }
    
    @Override
    @Transactional
    public void addUserToRoom(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));
        
        if (!chatRoom.getParticipants().contains(user)) {
            chatRoom.getParticipants().add(user);
            chatRoomRepository.save(chatRoom);
            log.info("Added user {} to room {}", user.getId(), roomId);
        } else {
            log.info("User {} is already in room {}", user.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public void removeUserFromRoom(String roomId, String userIdString) {
        Long userIdToRemove = Long.parseLong(userIdString);
        User userToRemove = userRepository.findById(userIdToRemove)
                .orElseThrow(() -> new RuntimeException("User to remove not found with id: " + userIdToRemove));
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Chat room not found: " + roomId));

        if (chatRoom.getParticipants().remove(userToRemove)) {
            chatRoomRepository.save(chatRoom);
            log.info("Removed user {} from room {}", userToRemove.getId(), roomId);
        } else {
            log.warn("User {} was not a participant in room {}. No action taken.", userToRemove.getId(), roomId);
        }
    }

    @Override
    @Transactional
    public void markAllMessagesInRoomAsRead(String roomId, String userIdString) {
        Long userId = Long.parseLong(userIdString);
        requireParticipant(roomId, userId);

        List<Message> allMessagesInRoom = messageRepository.findByChatRoomIdOrderByCreatedAtDesc(roomId, Pageable.unpaged()).getContent();
        
        List<Message> messagesToUpdate = new ArrayList<>();
        List<String> readMessageIds = new ArrayList<>();
        
        for (Message message : allMessagesInRoom) {
            if (message.getReadByUsers() == null) {
                message.setReadByUsers(new HashSet<>());
            }
            if (message.getReadByUsers().add(userId)) {
                messagesToUpdate.add(message);
                readMessageIds.add(message.getId());
            }
        }

        if (!messagesToUpdate.isEmpty()) {
            try {
                messageRepository.saveAll(messagesToUpdate);
                log.info("Successfully marked {} messages in room {} as read for user {}.", messagesToUpdate.size(), roomId, userIdString);
                
                // 읽음 상태 변경 알림 전송
                for (String messageId : readMessageIds) {
                    try {
                        notificationService.sendMessageReadStatusUpdate(messageId, roomId, userId);
                    } catch (Exception e) {
                        log.error("Failed to send read status update for messageId {}: {}", messageId, e.getMessage(), e);
                    }
                }
                
            } catch (Exception e) {
                log.error("Error saving messages after marking them as read for room {} user {}: {}", roomId, userIdString, e.getMessage(), e);
                throw new ChatException("Failed to save updated message read statuses.", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            log.info("No new messages to mark as read in room {} for user {}.", roomId, userIdString);
        }
    }
    
    @Override
    @Transactional
    public ChatRoomDto updateRoom(String roomId, Long requesterId, UpdateChatRoomRequest request) {
        ChatRoom chatRoom = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ChatException("Chat room not found: " + roomId, HttpStatus.NOT_FOUND));
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new ChatException("User not found: " + requesterId, HttpStatus.NOT_FOUND));
        if (chatRoom.getCreator() == null || !chatRoom.getCreator().getId().equals(requesterId)) {
            throw new ChatException("Only the room creator can update this room.", HttpStatus.FORBIDDEN);
        }
        if (request == null) {
            throw new ChatException("수정할 채팅방 정보를 입력해 줘.", HttpStatus.BAD_REQUEST);
        }
        if (request.getType() != null && request.getType() != chatRoom.getType()) {
            throw new ChatException("채팅방 종류는 만든 뒤 변경할 수 없어.", HttpStatus.BAD_REQUEST);
        }

        String resolvedName = request.resolvedName();
        if (resolvedName != null) {
            if (resolvedName.isBlank()) {
                throw new ChatException("Chat room name cannot be empty.", HttpStatus.BAD_REQUEST);
            }
            String normalizedName = resolvedName.trim();
            int maxNameLength = chatRoom.isPublicRoom() ? 80 : 255;
            if (normalizedName.length() > maxNameLength) {
                throw new ChatException(
                        "채팅방 이름은 " + maxNameLength + "자 이하로 입력해 줘.",
                        HttpStatus.BAD_REQUEST);
            }
            chatRoom.setName(normalizedName);
        }
        if (request.getDescription() != null) {
            String description = request.getDescription().trim();
            if (description.length() > 500) {
                throw new ChatException("채팅방 소개는 500자 이하로 입력해 줘.", HttpStatus.BAD_REQUEST);
            }
            chatRoom.setDescription(description);
        }
        Integer maxParticipants = request.resolvedMaxParticipants();
        if (maxParticipants != null) {
            if (chatRoom.isPublicRoom()) {
                throw new ChatException(
                        "공개 모임의 모집 인원은 모임 수정 화면에서 변경해 줘.",
                        HttpStatus.BAD_REQUEST);
            }
            if (maxParticipants < 2 || maxParticipants > 50) {
                throw new ChatException(
                        "최대 참여 인원은 2명 이상 50명 이하로 입력해 줘.",
                        HttpStatus.BAD_REQUEST);
            }
            if (maxParticipants < chatRoom.getParticipants().size()) {
                throw new ChatException("Maximum participants cannot be lower than the current participant count.", HttpStatus.CONFLICT);
            }
            chatRoom.setMaxParticipants(maxParticipants);
        }
        if (request.getStatus() != null) {
            chatRoom.setStatus(request.getStatus());
        }
        ChatRoom updatedRoom = chatRoomRepository.save(chatRoom);
        return ChatRoomDto.fromEntity(updatedRoom, requester, messageRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRoomDto findOneOnOneChatRoom(Long userId1, Long userId2) {
        log.info("[findOneOnOneChatRoom] Looking for existing 1:1 chat room between userId1: {} and userId2: {}", userId1, userId2);
        requireNotBlocked(userId1, userId2);
        
        User user1 = userRepository.findById(userId1)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId1, HttpStatus.NOT_FOUND));
        User user2 = userRepository.findById(userId2)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId2, HttpStatus.NOT_FOUND));
        
        // 두 사용자가 모두 참여하는 1:1 채팅방 찾기
        List<ChatRoom> user1Rooms = chatRoomRepository.findByParticipantsContainingAndType(user1, ChatRoomType.ONE_ON_ONE);
        
        for (ChatRoom room : user1Rooms) {
            // 정확히 2명만 있고, 그 중 한 명이 user2인지 확인
            if (room.getParticipants().size() == 2 && room.getParticipants().contains(user2)) {
                log.info("[findOneOnOneChatRoom] Found existing 1:1 chat room: roomId={}, roomName='{}'", room.getId(), room.getName());
                return ChatRoomDto.fromEntity(room, user1, messageRepository);
            }
        }
        
        log.info("[findOneOnOneChatRoom] No existing 1:1 chat room found between userId1: {} and userId2: {}", userId1, userId2);
        return null;
    }

    private void requireNotBlocked(Long firstId, Long secondId) {
        if (userBlockRepository != null && userBlockRepository.existsBetween(firstId, secondId)) {
            throw new ChatException("차단 관계인 사용자와는 1:1 채팅을 이용할 수 없어요.", HttpStatus.FORBIDDEN);
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Long> getUsersWithOneOnOneChatRooms(Long userId) {
        log.info("[getUsersWithOneOnOneChatRooms] Finding all users with 1:1 chat rooms for userId: {}", userId);
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ChatException("User not found with id: " + userId, HttpStatus.NOT_FOUND));
        
        // 해당 사용자가 참여하는 모든 1:1 채팅방 조회
        List<ChatRoom> oneOnOneRooms = chatRoomRepository.findByParticipantsContainingAndType(user, ChatRoomType.ONE_ON_ONE);
        
        List<Long> otherUserIds = new ArrayList<>();
        for (ChatRoom room : oneOnOneRooms) {
            // 정확히 2명만 있는 1:1 채팅방에서 상대방 찾기
            if (room.getParticipants().size() == 2) {
                for (User participant : room.getParticipants()) {
                    if (!participant.getId().equals(userId)) {
                        otherUserIds.add(participant.getId());
                        break;
                    }
                }
            }
        }
        
        log.info("[getUsersWithOneOnOneChatRooms] Found {} users with existing 1:1 chat rooms for userId: {}", otherUserIds.size(), userId);
        return otherUserIds;
    }
}
