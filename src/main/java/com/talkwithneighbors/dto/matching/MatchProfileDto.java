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
    private Integer age;
    private String gender;
    private List<String> interests;
    private String bio;
    private String imageUrl;
    private String profileImage;
    private LocationDto location;
    private Double distance;
    private Integer compatibilityScore;
    private List<String> sharedInterests;

    public static MatchProfileDto fromUser(User user, Double distance, String matchId) {
        return fromUser(user, distance, matchId, null, List.of());
    }

    public static MatchProfileDto fromUser(
            User user,
            Double distance,
            String matchId,
            Integer compatibilityScore,
            List<String> sharedInterests
    ) {
        MatchProfileDto dto = new MatchProfileDto();
        dto.setId(user.getId().toString());
        dto.setMatchId(matchId);
        dto.setUsername(user.getUsername());
        dto.setAge(user.getAge());
        dto.setGender(user.getGender());
        dto.setInterests(user.getInterests());
        dto.setBio(user.getBio());
        dto.setImageUrl(user.getProfileImage());
        dto.setProfileImage(user.getProfileImage());
        
        LocationDto locationDto = new LocationDto();
        locationDto.setLatitude(user.getLatitude());
        locationDto.setLongitude(user.getLongitude());
        locationDto.setAddress(user.getAddress());
        dto.setLocation(locationDto);
        
        dto.setDistance(distance);
        dto.setCompatibilityScore(compatibilityScore);
        dto.setSharedInterests(sharedInterests);
        
        return dto;
    }

    public static MatchProfileDto fromUser(User user, Double distance) {
        return fromUser(user, distance, null);
    }
} 
