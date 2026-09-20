package com.talkwithneighbors.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄링 관련 설정
 *
 * 이 클래스는 어떤 서비스에도 의존하지 않는다. WebSocket 메시지 브로커가 기동하면서
 * TaskScheduler 빈을 찾기 때문에, 여기에 서비스를 주입하면 그 서비스가 다시
 * 브로커를 필요로 하는 순환 참조가 만들어진다. 실제 스케줄 작업은
 * {@code scheduler} 패키지의 컴포넌트가 가진다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * {@code @Scheduled} 작업 전용 스케줄러.
     *
     * 이 빈이 없으면 Spring은 컨텍스트에 있는 TaskScheduler를 집어 쓰는데,
     * 예전에는 그것이 WebSocket 하트비트 풀이라 아웃박스 폴링과 정리 작업이 하트비트를 지연시켰다.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sched-");
        return scheduler;
    }
}
