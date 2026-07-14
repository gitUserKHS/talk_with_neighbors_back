package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 사용자 정보를 관리하는 엔티티 클래스
 * 사용자의 기본 정보, 매칭 관련 정보, 위치 정보 등을 저장합니다.
 */
@JsonIgnoreProperties({"interests", "createdRooms", "joinedRooms", "sentMessages"})
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    /**
     * 사용자의 고유 식별자
     * 자동 증가하는 ID 값입니다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사용자의 이메일 주소
     * 유니크한 값으로 설정됩니다.
     */
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * 사용자의 사용자 이름
     * 유니크한 값으로 설정됩니다.
     */
    @Column(nullable = false, unique = true)
    private String username;

    /**
     * 사용자의 비밀번호
     * 암호화되어 저장됩니다.
     */
    @Column(nullable = false)
    private String password;

    /**
     * SYSTEM identities own transparent first-party content but can never log in
     * or participate in member matching. The database default keeps legacy rows
     * as normal members when Hibernate adds this column in place.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, columnDefinition = "varchar(20) default 'MEMBER'")
    @Builder.Default
    private UserAccountType accountType = UserAccountType.MEMBER;

    @Column(name = "password_login_enabled", nullable = false,
            columnDefinition = "boolean default true")
    @Builder.Default
    private Boolean passwordLoginEnabled = true;

    /**
     * 사용자의 프로필 이미지 URL
     */
    private String profileImage;

    /**
     * 마지막 로그인 시간
     */
    private LocalDateTime lastLogin;

    // 매칭 관련 필드
    /**
     * 사용자의 나이
     */

    private Integer age;

    /**
     * 사용자의 성별
     */

    private String gender;
    
    /**
     * 사용자의 관심사 목록
     * 별도의 테이블에 저장됩니다.
     */
    @ElementCollection
    @CollectionTable(name = "user_interests", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "interest")
    @JsonIgnore
    @Builder.Default
    private List<String> interests = new ArrayList<>();
    
    /**
     * 사용자의 자기소개
     * TEXT 타입으로 저장됩니다.
     */
    @Column(columnDefinition = "TEXT")
    private String bio;
    
    /**
     * 사용자의 위치 정보
     */
    @Column(nullable = false)
    private Double latitude;
      
    @Column(nullable = false)
    private Double longitude;
    
    @Column(nullable = false)
    private String address;

    // 매칭 설정 관련 필드는 DTO로 이동
    // maxDistance, minAge, maxAge, preferredGender, preferredInterests 필드 제거

    @Column(name = "is_online")
    private Boolean isOnline;
    
    @Column(name = "last_online_at")
    private LocalDateTime lastOnlineAt;

    @Column(name = "last_location_update")
    private LocalDateTime lastLocationUpdate;

    @Column(name = "profile_discoverable")
    @Builder.Default
    private Boolean profileDiscoverable = true;

    @Column(name = "show_neighborhood")
    @Builder.Default
    private Boolean showNeighborhood = true;

    @Column(name = "match_notifications_enabled")
    @Builder.Default
    private Boolean matchNotificationsEnabled = true;

    @Column(name = "chat_notifications_enabled")
    @Builder.Default
    private Boolean chatNotificationsEnabled = true;

    @Column(name = "meetup_notifications_enabled")
    @Builder.Default
    private Boolean meetupNotificationsEnabled = true;

    @JsonIgnore
    @OneToMany(mappedBy = "creator", cascade = CascadeType.ALL)
    @Builder.Default
    private List<ChatRoom> createdRooms = new ArrayList<>();

    @JsonIgnore
    @ManyToMany(mappedBy = "participants")
    @Builder.Default
    private List<ChatRoom> joinedRooms = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "sender", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Message> sentMessages = new ArrayList<>();

    @Transient
    public boolean isProfileComplete() {
        return age != null &&
               gender != null && !gender.isEmpty() &&
               interests != null && !interests.isEmpty() &&
               latitude != null &&
               longitude != null &&
               address != null && !address.isEmpty();
    }

    @PrePersist
    protected void applyAccountDefaults() {
        if (accountType == null) {
            accountType = UserAccountType.MEMBER;
        }
        if (passwordLoginEnabled == null) {
            passwordLoginEnabled = true;
        }
    }
}

/**
 * 문자열 리스트를 데이터베이스에 저장하기 위한 컨버터 클래스
 * List<String>을 콤마로 구분된 문자열로 변환하여 저장합니다.
 */
@Converter
class StringListConverter implements AttributeConverter<List<String>, String> {
    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return attribute == null ? null : String.join(",", attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        return dbData == null ? null : List.of(dbData.split(","));
    }
}
