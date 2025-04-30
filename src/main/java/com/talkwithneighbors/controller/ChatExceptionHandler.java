package com.talkwithneighbors.controller;

import com.talkwithneighbors.exception.ChatException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(com.talkwithneighbors.exception.MatchingException.class)
    public ResponseEntity<Map<String, Object>> handleMatchingException(com.talkwithneighbors.exception.MatchingException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(error);
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, Object>> handleChatException(ChatException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(error);
    }
}


