package com.talkwithneighbors.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class);

    @Test
    void unqualifiedAsyncMethodsRunOnABoundedPool() {
        contextRunner.run(context -> {
            Executor executor = context.getBean(AsyncConfigurer.class).getAsyncExecutor();

            // AsyncConfigurer가 없으면 Spring은 TaskExecutor 빈이 여럿이라 SimpleAsyncTaskExecutor로 물러난다.
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaxPoolSize()).isEqualTo(4);
            assertThat(pool.getThreadNamePrefix()).isEqualTo("async-");
            assertThat(context.getBean("chatNotificationExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);
        });
    }

    @Test
    void saturatedPoolRejectsInsteadOfRunningOnTheCaller() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor pool =
                    (ThreadPoolTaskExecutor) context.getBean(AsyncConfigurer.class).getAsyncExecutor();
            RejectedExecutionHandler handler = pool.getThreadPoolExecutor().getRejectedExecutionHandler();

            // 요청 스레드에서도 호출되므로 CallerRuns로 요청을 붙들어서는 안 된다.
            assertThat(handler).isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThatThrownBy(() -> handler.rejectedExecution(() -> { }, pool.getThreadPoolExecutor()))
                    .isInstanceOf(RejectedExecutionException.class);
        });
    }

    @Test
    void scheduledJobsGetTheirOwnSchedulerPool() {
        // 스케줄러 풀을 정의하는 설정은 어떤 서비스도 주입받지 않는다. 주입받으면 그 서비스가
        // 필요로 하는 WebSocket 브로커가 다시 이 TaskScheduler를 찾아 순환 참조가 된다.
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class)
                .run(context -> {
                    ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);

                    // getPoolSize()는 설정값이 아니라 지금 살아 있는 스레드 수라 작업 전에는 의미가 없다.
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("sched-");
                });
    }

    @Test
    void webSocketConfigNoLongerExportsTheHeartbeatSchedulerAsABean() {
        // 하트비트 풀이 빈으로 노출되면 @Scheduled 작업이 그 풀을 함께 쓰게 된다.
        assertThat(WebSocketConfig.class.getDeclaredMethods())
                .filteredOn(method -> method.isAnnotationPresent(Bean.class))
                .noneMatch(method -> TaskScheduler.class.isAssignableFrom(method.getReturnType()));
    }
}
