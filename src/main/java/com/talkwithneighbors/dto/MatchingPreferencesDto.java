package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.User;
import com.talkwithneighbors.entity.MatchingPreferences;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;

/**
 * 사용자의 매칭 설정을 담는 DTO 클래스
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchingPreferencesDto {
    /**
     * 최대 매칭 거리 (킬로미터)
     */
    private Double maxDistance;

    /**
     * 선호하는 나이 범위
     */
    private Integer minAge;
    private Integer maxAge;

    /**
     * 선호하는 성별
     */
    private String preferredGender;
    
    /**
     * 선호하는 관심사 목록
     */
    private List<String> preferredInterests;
    
    /**
     * MatchingPreferences 엔티티에서 MatchingPreferencesDto를 생성합니다.
     */
    public static MatchingPreferencesDto fromEntity(MatchingPreferences preferences) {
        if (preferences == null) {
            return null;
        }
        
        return MatchingPreferencesDto.builder()
                .maxDistance(preferences.getMaxDistance())
                .minAge(preferences.getMinAge())
                .maxAge(preferences.getMaxAge())
                .preferredGender(preferences.getPreferredGender())
                .preferredInterests(preferences.getPreferredInterests())
                .build();
    }
    
    /**
     * MatchingPreferences 엔티티를 생성합니다.
     */
    public MatchingPreferences toEntity(User user) {
        return MatchingPreferences.builder()
                .user(user)
                .maxDistance(this.maxDistance)
                .minAge(this.minAge)
                .maxAge(this.maxAge)
                .preferredGender(this.preferredGender)
                .preferredInterests(this.preferredInterests)
                .build();
    }
}