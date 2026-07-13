package com.talkwithneighbors.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FeedPostMedia {
    @Column(name = "media_url", nullable = false, length = 1000)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    private FeedMediaType type;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "media_width")
    private Integer width;

    @Column(name = "media_height")
    private Integer height;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    public FeedPostMedia(String url, FeedMediaType type) {
        this.url = url;
        this.type = type;
    }
}
