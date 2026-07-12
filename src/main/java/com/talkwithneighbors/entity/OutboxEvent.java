package com.talkwithneighbors.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "outbox_events",
        indexes = @Index(name = "idx_outbox_unpublished", columnList = "published_at,occurred_at")
)
@Getter
@NoArgsConstructor
public class OutboxEvent {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public OutboxEvent(
            String id,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payload,
            LocalDateTime occurredAt
    ) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void registerFailure(String error) {
        this.retryCount += 1;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
