package com.talkwithneighbors.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "meetup_waitlist", uniqueConstraints = @UniqueConstraint(
        name = "uk_meetup_waitlist_room_user", columnNames = {"room_id", "user_id"}),
        indexes = @Index(name = "idx_meetup_waitlist_order", columnList = "room_id,created_at"))
@Getter
@NoArgsConstructor
public class MeetupWaitlistEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "room_id", nullable = false)
    private ChatRoom room;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MeetupWaitlistEntry(ChatRoom room, User user) { this.room = room; this.user = user; }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
