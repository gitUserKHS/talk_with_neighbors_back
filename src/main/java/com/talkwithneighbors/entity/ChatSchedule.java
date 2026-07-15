package com.talkwithneighbors.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "chat_schedules",
        indexes = {
                @Index(name = "idx_chat_schedule_room_start", columnList = "room_id,status,starts_at"),
                @Index(name = "idx_chat_schedule_creator", columnList = "creator_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ChatSchedule {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    @JsonIgnore
    private ChatRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    @JsonIgnore
    private User creator;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(length = 100)
    private String location;

    @Column(name = "location_address", length = 255)
    private String locationAddress;

    private Double latitude;

    private Double longitude;

    @Column(name = "kakao_place_id", length = 64)
    private String kakaoPlaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatScheduleStatus status = ChatScheduleStatus.SCHEDULED;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("respondedAt ASC, id ASC")
    @JsonIgnore
    private List<ChatScheduleRsvp> rsvps = new ArrayList<>();

    public void addRsvp(ChatScheduleRsvp rsvp) {
        rsvps.add(rsvp);
        rsvp.setSchedule(this);
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ChatScheduleStatus.SCHEDULED;
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
