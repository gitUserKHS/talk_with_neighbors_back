package com.talkwithneighbors.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class LocationDto {
    private Double latitude;
    private Double longitude;
    private String address;
} 