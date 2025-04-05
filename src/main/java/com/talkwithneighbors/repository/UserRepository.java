package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 사용자 정보를 관리하는 리포지토리 인터페이스
 * 사용자 관련 데이터베이스 작업을 처리합니다.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 이메일로 사용자를 조회합니다.
     * 
     * @param email 사용자 이메일
     * @return 사용자 정보 (Optional)
     */
    Optional<User> findByEmail(String email);

    /**
     * 이메일이 이미 존재하는지 확인합니다.
     * 
     * @param email 확인할 이메일
     * @return 존재 여부
     */
    boolean existsByEmail(String email);

    /**
     * 닉네임이 이미 존재하는지 확인합니다.
     * 
     * @param username 확인할 닉네임
     * @return 존재 여부
     */
    boolean existsByUsername(String username);

    /**
     * 특정 위치 주변의 사용자들을 조회합니다.
     * Haversine 공식을 사용하여 지구 표면의 거리를 계산합니다.
     * 
     * @param latitude 위도
     * @param longitude 경도
     * @param radius 검색 반경 (킬로미터)
     * @return 주변 사용자 목록
     */
    @Query(value = """
            SELECT * FROM users u
            WHERE (
                6371 * acos(
                    cos(radians(:latitude)) * cos(radians(u.latitude)) *
                    cos(radians(u.longitude) - radians(:longitude)) +
                    sin(radians(:latitude)) * sin(radians(u.latitude))
                )
            ) <= :radius
            """, nativeQuery = true)
    List<User> findNearbyUsers(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("radius") Double radius
    );

    List<User> findByIsOnlineTrueAndLastOnlineAtBefore(LocalDateTime dateTime);
} 