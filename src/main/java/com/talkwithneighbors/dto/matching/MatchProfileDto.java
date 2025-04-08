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
    private String nickname;
    private String age;
    private String gender;
    private List<String> interests;
    private String bio;
    private String imageUrl;
    private LocationDto location;
    private Double distance;

    public static MatchProfileDto fromUser(User user, Double distance) {
        MatchProfileDto dto = new MatchProfileDto();
        dto.setId(user.getId().toString());
        dto.setNickname(user.getNickname());
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
} 