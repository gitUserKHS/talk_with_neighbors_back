package com.talkwithneighbors.dto.matching;

import com.talkwithneighbors.dto.LocationDto;
import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.User;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class MatchingPreferencesDto {
    private LocationDto location;
    private Double maxDistance;
    private Integer[] ageRange;
    private String gender;
    private List<String> interests;

    public MatchingPreferences toEntity(User user) {
        MatchingPreferences preferences = new MatchingPreferences();
        preferences.setUser(user);
        preferences.setLatitude(location.getLatitude());
        preferences.setLongitude(location.getLongitude());
        preferences.setAddress(location.getAddress());
        preferences.setMaxDistance(maxDistance);
        preferences.setMinAge(ageRange[0]);
        preferences.setMaxAge(ageRange[1]);
        preferences.setPreferredGender(gender);
        preferences.setPreferredInterests(interests);
        return preferences;
    }
} 