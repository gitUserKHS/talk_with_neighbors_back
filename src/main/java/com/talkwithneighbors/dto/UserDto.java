package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.User;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class UserDto {
    private String id;
    private String username;
    private String email;
    private String profileImage;
    private Integer age;
    private String gender;
    private String bio;
    private Double latitude;
    private Double longitude;
    private String address;
    private List<String> interests;
    private boolean nicknameSetupRequired;
    private boolean profileComplete;
    private int profileCompletion;

    public static UserDto fromEntity(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId().toString());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setProfileImage(user.getProfileImage());
        dto.setAge(user.getAge());
        dto.setGender(user.getGender());
        dto.setBio(user.getBio());
        dto.setLatitude(user.getLatitude());
        dto.setLongitude(user.getLongitude());
        dto.setAddress(user.getAddress());
        dto.setInterests(user.getInterests());
        boolean nicknameSetupRequired = Boolean.TRUE.equals(user.getNicknameSetupRequired());
        dto.setNicknameSetupRequired(nicknameSetupRequired);
        dto.setProfileComplete(user.isProfileComplete() && !nicknameSetupRequired);
        int completed = 0;
        if (user.getAge() != null) completed++;
        if (user.getGender() != null && !user.getGender().isBlank()) completed++;
        if (user.getInterests() != null && !user.getInterests().isEmpty()) completed++;
        if (user.getLatitude() != null && user.getLongitude() != null) completed++;
        if (user.getAddress() != null && !user.getAddress().isBlank()) completed++;
        dto.setProfileCompletion(completed * 20);
        return dto;
    }
} 
