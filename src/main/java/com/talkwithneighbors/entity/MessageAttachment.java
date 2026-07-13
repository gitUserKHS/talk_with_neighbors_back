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
public class MessageAttachment {
    @Column(name = "media_url", nullable = false, length = 1000)
    private String url;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "attachment_type", nullable = false, length = 16)
    private ChatAttachmentType type;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "media_width")
    private Integer width;

    @Column(name = "media_height")
    private Integer height;

    @Column(name = "duration_seconds")
    private Double durationSeconds;
}
