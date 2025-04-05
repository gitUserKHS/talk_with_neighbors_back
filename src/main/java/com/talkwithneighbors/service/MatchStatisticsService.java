package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.repository.MatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매칭 통계를 관리하는 서비스 클래스
 * 사용자별 및 시스템 전체의 매칭 통계를 계산하고 제공합니다.
 */
@Service
@RequiredArgsConstructor
public class MatchStatisticsService {

    /**
     * 매칭 정보를 관리하는 리포지토리
     */
    private final MatchRepository matchRepository;

    /**
     * 특정 사용자의 매칭 통계를 조회합니다.
     * 
     * @param userId 사용자 ID
     * @return 통계 정보 (총 매칭 수, 상태별 분포, 평균 응답 시간)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserMatchStatistics(Long userId) {
        List<Match> userMatches = matchRepository.findByUserId(userId);
        
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalMatches", userMatches.size());
        
        // 상태별 매칭 수 계산
        Map<MatchStatus, Long> statusCount = new HashMap<>();
        for (MatchStatus status : MatchStatus.values()) {
            statusCount.put(status, userMatches.stream()
                    .filter(match -> match.getStatus() == status)
                    .count());
        }
        statistics.put("statusDistribution", statusCount);
        
        // 평균 응답 시간 계산 (수락/거절된 매칭만)
        double avgResponseTime = userMatches.stream()
                .filter(match -> match.getStatus() == MatchStatus.ACCEPTED || 
                               match.getStatus() == MatchStatus.REJECTED)
                .mapToLong(match -> 
                    java.time.Duration.between(
                        match.getCreatedAt(), 
                        match.getRespondedAt()
                    ).toMinutes())
                .average()
                .orElse(0.0);
        statistics.put("averageResponseTimeMinutes", avgResponseTime);
        
        return statistics;
    }

    /**
     * 시스템 전체의 매칭 통계를 조회합니다.
     * 
     * @return 통계 정보 (총 매칭 수, 상태별 분포, 시간대별 분포)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemMatchStatistics() {
        List<Match> allMatches = matchRepository.findAll();
        
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalMatches", allMatches.size());
        
        // 상태별 매칭 수 계산
        Map<MatchStatus, Long> statusCount = new HashMap<>();
        for (MatchStatus status : MatchStatus.values()) {
            statusCount.put(status, allMatches.stream()
                    .filter(match -> match.getStatus() == status)
                    .count());
        }
        statistics.put("statusDistribution", statusCount);
        
        // 시간대별 매칭 생성 통계 계산
        Map<Integer, Long> hourlyDistribution = new HashMap<>();
        for (int i = 0; i < 24; i++) {
            final int hour = i;
            hourlyDistribution.put(hour, allMatches.stream()
                    .filter(match -> match.getCreatedAt().getHour() == hour)
                    .count());
        }
        statistics.put("hourlyDistribution", hourlyDistribution);
        
        return statistics;
    }
} 