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
    /**
     * URL 조회용 인덱스 {@code idx_message_attachments_media_url}과
     * {@code idx_message_attachments_thumbnail_url}은 운영 마이그레이션
     * {@code V2026092001__add_hot_path_indexes.sql}이 191자 prefix 인덱스로만 만든다.
     * {@code @Index}는 prefix 길이를 표현할 수 없고 utf8mb4 VARCHAR(1000) 전체 키는
     * MySQL의 3072바이트 한도를 넘으므로 여기서는 미러하지 않는다.
     */
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
