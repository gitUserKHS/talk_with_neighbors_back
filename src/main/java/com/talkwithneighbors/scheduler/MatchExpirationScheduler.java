package com.talkwithneighbors.scheduler;

import com.talkwithneighbors.entity.Match;
import com.talkwithneighbors.entity.MatchStatus;
import com.talkwithneighbors.repository.MatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 매칭 만료를 관리하는 스케줄러 클래스
 * 주기적으로 만료된 매칭을 확인하고 처리합니다.
 */
@Component
@RequiredArgsConstructor
public class MatchExpirationScheduler {

    /**
     * 매칭 정보를 관리하는 리포지토리
     */
    private final MatchRepository matchRepository;

    /**
     * WebSocket 메시지를 전송하기 위한 템플릿
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 만료된 매칭을 확인하고 처리하는 스케줄링 작업
     * 1분마다 실행되며, PENDING 상태의 만료된 매칭을 찾아 EXPIRED 상태로 변경합니다.
     * 변경된 매칭의 양쪽 사용자에게 WebSocket을 통해 알림을 전송합니다.
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    @Transactional
    public void checkExpiredMatches() {
        LocalDateTime now = LocalDateTime.now();
        List<Match> expiredMatches = matchRepository.findByStatusAndExpiresAtBefore(
                MatchStatus.PENDING, now);

        for (Match match : expiredMatches) {
            // 매칭 상태를 EXPIRED로 변경
            match.setStatus(MatchStatus.EXPIRED);
            match.setRespondedAt(now);
            matchRepository.save(match);

            // 양쪽 사용자에게 만료 알림 전송
            messagingTemplate.convertAndSendToUser(
                match.getUser1().getId().toString(),
                "/queue/match-expired",
                match.getId()
            );
            messagingTemplate.convertAndSendToUser(
                match.getUser2().getId().toString(),
                "/queue/match-expired",
                match.getId()
            );
        }
    }
} 