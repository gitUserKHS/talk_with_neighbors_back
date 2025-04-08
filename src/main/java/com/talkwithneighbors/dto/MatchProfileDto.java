package com.talkwithneighbors.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MatchProfileDto {
    private Long id;
    private String username;
    private List<String> interests;
} 