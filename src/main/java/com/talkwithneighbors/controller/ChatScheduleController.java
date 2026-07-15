package com.talkwithneighbors.controller;

import com.talkwithneighbors.dto.schedule.CancelChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.ChatScheduleDto;
import com.talkwithneighbors.dto.schedule.CreateChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.UpdateChatScheduleRequest;
import com.talkwithneighbors.dto.schedule.UpdateChatScheduleRsvpRequest;
import com.talkwithneighbors.security.UserSession;
import com.talkwithneighbors.service.ChatScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/rooms/{roomId}/schedules")
@RequiredArgsConstructor
public class ChatScheduleController {
    private final ChatScheduleService chatScheduleService;

    @PostMapping
    public ResponseEntity<ChatScheduleDto> create(
            @PathVariable String roomId,
            @Valid @RequestBody CreateChatScheduleRequest request,
            UserSession session
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatScheduleService.create(roomId, session.getUserId(), request));
    }

    @GetMapping
    public ResponseEntity<List<ChatScheduleDto>> list(
            @PathVariable String roomId,
            UserSession session
    ) {
        return ResponseEntity.ok(chatScheduleService.list(roomId, session.getUserId()));
    }

    @GetMapping("/{scheduleId}")
    public ResponseEntity<ChatScheduleDto> get(
            @PathVariable String roomId,
            @PathVariable String scheduleId,
            UserSession session
    ) {
        return ResponseEntity.ok(chatScheduleService.get(
                roomId, scheduleId, session.getUserId()));
    }

    @PatchMapping("/{scheduleId}")
    public ResponseEntity<ChatScheduleDto> update(
            @PathVariable String roomId,
            @PathVariable String scheduleId,
            @Valid @RequestBody UpdateChatScheduleRequest request,
            UserSession session
    ) {
        return ResponseEntity.ok(chatScheduleService.update(
                roomId, scheduleId, session.getUserId(), request));
    }

    @PostMapping("/{scheduleId}/cancel")
    public ResponseEntity<ChatScheduleDto> cancel(
            @PathVariable String roomId,
            @PathVariable String scheduleId,
            @Valid @RequestBody CancelChatScheduleRequest request,
            UserSession session
    ) {
        return ResponseEntity.ok(chatScheduleService.cancel(
                roomId, scheduleId, session.getUserId(), request));
    }

    @PutMapping("/{scheduleId}/rsvp")
    public ResponseEntity<ChatScheduleDto> rsvp(
            @PathVariable String roomId,
            @PathVariable String scheduleId,
            @Valid @RequestBody UpdateChatScheduleRsvpRequest request,
            UserSession session
    ) {
        return ResponseEntity.ok(chatScheduleService.rsvp(
                roomId, scheduleId, session.getUserId(), request));
    }
}
