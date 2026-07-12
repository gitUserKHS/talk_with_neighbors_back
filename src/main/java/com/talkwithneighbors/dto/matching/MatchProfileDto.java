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
    private List<String> explanationReasons;

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
        // 공개 프로필에는 정확한 좌표를 노출하지 않는다. 거리는 서버에서 계산하고 동네 단위 주소만 제공한다.
        locationDto.setLatitude(null);
        locationDto.setLongitude(null);
        locationDto.setAddress(Boolean.FALSE.equals(user.getShowNeighborhood()) ? null : generalizeAddress(user.getAddress()));
        dto.setLocation(locationDto);
        
        dto.setDistance(distance);
        dto.setCompatibilityScore(compatibilityScore);
        dto.setSharedInterests(sharedInterests);
        
        return dto;
    }

    private static String generalizeAddress(String address) {
        if (address == null || address.isBlank()) return null;
        String[] parts = address.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(parts, Math.min(parts.length, 2)));
    }

    public static MatchProfileDto fromUser(User user, Double distance) {
        return fromUser(user, distance, null);
    }
} 
