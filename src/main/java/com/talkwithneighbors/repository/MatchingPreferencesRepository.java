package com.talkwithneighbors.repository;

import com.talkwithneighbors.entity.MatchingPreferences;
import com.talkwithneighbors.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MatchingPreferencesRepository extends JpaRepository<MatchingPreferences, Long> {
    /**
     * 사용자 ID로 매칭 설정을 조회합니다.
     */
    Optional<MatchingPreferences> findByUserId(Long userId);
    
    /**
     * 사용자로 매칭 설정을 조회합니다.
     */
    Optional<MatchingPreferences> findByUser(User user);
} 