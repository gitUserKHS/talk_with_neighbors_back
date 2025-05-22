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
        return dto;
    }
} 