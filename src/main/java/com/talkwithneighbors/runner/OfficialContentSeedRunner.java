package com.talkwithneighbors.runner;

import com.talkwithneighbors.service.OfficialContentMediaPublisher;
import com.talkwithneighbors.service.OfficialContentSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.official-content", name = "enabled", havingValue = "true")
public class OfficialContentSeedRunner implements ApplicationRunner {
    private final OfficialContentMediaPublisher mediaPublisher;
    private final OfficialContentSeedService seedService;

    @Override
    public void run(ApplicationArguments args) {
        mediaPublisher.publish();
        seedService.sync();
        log.info("Official first-party content is ready");
    }
}
