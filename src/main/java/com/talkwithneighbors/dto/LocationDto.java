package com.talkwithneighbors.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationDto {
    private Double latitude;
    private Double longitude;
    private String address;
} 