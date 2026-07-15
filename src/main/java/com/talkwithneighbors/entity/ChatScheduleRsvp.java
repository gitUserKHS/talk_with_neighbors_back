package com.talkwithneighbors.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "chat_schedule_rsvps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_schedule_rsvp_schedule_user",
                columnNames = {"schedule_id", "user_id"}),
        indexes = @Index(
                name = "idx_chat_schedule_rsvp_status",
                columnList = "schedule_id,status,responded_at")
)
@Getter
@Setter
@NoArgsConstructor
public class ChatScheduleRsvp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    @JsonIgnore
    private ChatSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatScheduleRsvpStatus status;

    @Column(name = "responded_at", nullable = false)
    private Instant respondedAt;

    public ChatScheduleRsvp(ChatSchedule schedule, User user, ChatScheduleRsvpStatus status) {
        this.schedule = schedule;
        this.user = user;
        this.status = status;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        respondedAt = Instant.now();
    }
}
