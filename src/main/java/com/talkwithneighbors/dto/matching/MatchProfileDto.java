package com.talkwithneighbors.dto.matching;

import com.talkwithneighbors.dto.LocationDto;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class MatchProfileDto {
    private String id;
    private String matchId;
    private String username;
    private String age;
    private String gender;
    private List<String> interests;
    private String bio;
    private String imageUrl;
    private LocationDto location;
    private Double distance;

    public static MatchProfileDto fromUser(User user, Double distance, String matchId) {
        MatchProfileDto dto = new MatchProfileDto();
        dto.setId(user.getId().toString());
        dto.setMatchId(matchId);
        dto.setUsername(user.getUsername());
        dto.setAge(user.getAge() != null ? user.getAge().toString() : null);
        dto.setGender(user.getGender());
        dto.setInterests(user.getInterests());
        dto.setBio(user.getBio());
        dto.setImageUrl(user.getProfileImage());
        
        LocationDto locationDto = new LocationDto();
        locationDto.setLatitude(user.getLatitude());
        locationDto.setLongitude(user.getLongitude());
        locationDto.setAddress(user.getAddress());
        dto.setLocation(locationDto);
        
        dto.setDistance(distance);
        
        return dto;
    }

    public static MatchProfileDto fromUser(User user, Double distance) {
        return fromUser(user, distance, null);
    }
} 