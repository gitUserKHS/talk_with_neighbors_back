package com.talkwithneighbors.outbox;

import com.talkwithneighbors.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventProcessor outboxEventProcessor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliverAfterCommit(OutboxEventStored event) {
        outboxEventProcessor.process(event.eventId());
    }

    @Scheduled(fixedDelayString = "${app.outbox.retry-interval-ms:5000}")
    public void retryPendingEvents() {
        outboxEventRepository.findTop100ByPublishedAtIsNullOrderByOccurredAtAsc()
                .forEach(event -> outboxEventProcessor.process(event.getId()));
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 0 3 * * *}")
    public void cleanupPublishedEvents() {
        long deleted = outboxEventProcessor.cleanupPublishedEvents(7);
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than 7 days", deleted);
        }
    }
}
