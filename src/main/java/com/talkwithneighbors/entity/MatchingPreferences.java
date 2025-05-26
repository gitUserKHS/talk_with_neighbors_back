package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;

/**
 * 사용자의 매칭 설정을 저장하는 엔티티 클래스
 */
@Entity
@Table(name = "matching_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchingPreferences {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 매칭 설정을 소유하는 사용자
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;
    
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
     * 별도의 테이블에 저장됩니다.
     */
    @ElementCollection
    @CollectionTable(name = "matching_preferences_interests", joinColumns = @JoinColumn(name = "preferences_id"))
    @Column(name = "interest")
    private List<String> preferredInterests;
} 