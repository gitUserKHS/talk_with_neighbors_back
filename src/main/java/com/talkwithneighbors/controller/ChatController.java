package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.ChatRoomDto;
import com.talkwithneighbors.dto.MessageDto;
import com.talkwithneighbors.dto.chat.CreateRoomRequestDto;
import com.talkwithneighbors.security.RequireLogin;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @RequireLogin
    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoomDto>> getRooms(@SessionAttribute("userSession") UserSession userSession) {
        return ResponseEntity.ok(chatService.getUserChatRooms(userSession.getUserId()));
    }

    @RequireLogin
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoomDto> getRoom(@PathVariable String roomId, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        return ResponseEntity.ok(chatService.getRoom(roomId, userSession.getUserId()));
    }

    @RequireLogin
    @PostMapping("/rooms")
    public ResponseEntity<ChatRoomDto> createRoom(@RequestBody CreateRoomRequestDto request, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        return ResponseEntity.ok(chatService.createRoom(request, userSession.getUserId()));
    }

    @RequireLogin
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<MessageDto>> getMessages(@PathVariable String roomId, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        return ResponseEntity.ok(chatService.getMessages(roomId, userSession.getUserId()));
    }

    @RequireLogin
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessageDto> sendMessage(
            @PathVariable String roomId,
            @RequestBody MessageDto request,
            HttpSession session
    ) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        return ResponseEntity.ok(chatService.sendMessage(roomId, request.getContent(), userSession.getUserId()));
    }

    @RequireLogin
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<Void> deleteRoom(@PathVariable String roomId, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        chatService.deleteRoom(roomId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }

    @RequireLogin
    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable String roomId, HttpSession session) {
        UserSession userSession = (UserSession) session.getAttribute("user");
        chatService.leaveRoom(roomId, userSession.getUserId());
        return ResponseEntity.ok().build();
    }
} 