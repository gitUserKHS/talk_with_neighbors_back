package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_feedback", uniqueConstraints = @UniqueConstraint(
        name = "uk_recommendation_feedback_pair", columnNames = {"user_id", "candidate_id"}))
@Getter
@NoArgsConstructor
public class RecommendationFeedback {
    public enum Sentiment { POSITIVE, NEGATIVE }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "candidate_id") private User candidate;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Sentiment sentiment;
    @Column(length = 100) private String reason;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    public RecommendationFeedback(User user, User candidate) { this.user = user; this.candidate = candidate; }
    public void update(Sentiment sentiment, String reason) {
        this.sentiment = sentiment;
        this.reason = reason == null ? null : reason.trim();
        this.updatedAt = LocalDateTime.now();
    }
    @PrePersist void onCreate() { if (updatedAt == null) updatedAt = LocalDateTime.now(); }
}
