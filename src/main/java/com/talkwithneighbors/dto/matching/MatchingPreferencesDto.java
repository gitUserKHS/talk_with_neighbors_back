package com.talkwithneighbors.dto.matching;

// import com.talkwithneighbors.dto.LocationDto; // 제거
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
    // private LocationDto location; // 제거
    private Double maxDistance;
    private Integer[] ageRange;
    private String gender;
    private List<String> interests;

    public MatchingPreferences toEntity(User user) {
        MatchingPreferences preferences = new MatchingPreferences();
        preferences.setUser(user);
        // preferences.setLatitude(location.getLatitude()); // 제거
        // preferences.setLongitude(location.getLongitude()); // 제거
        // preferences.setAddress(location.getAddress()); // 제거
        preferences.setMaxDistance(maxDistance);
        preferences.setMinAge(ageRange[0]);
        preferences.setMaxAge(ageRange[1]);
        preferences.setPreferredGender(gender);
        preferences.setPreferredInterests(interests);
        return preferences;
    }
} 