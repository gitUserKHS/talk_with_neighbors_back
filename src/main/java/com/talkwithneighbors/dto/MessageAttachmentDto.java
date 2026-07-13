package com.talkwithneighbors.dto;

import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.MessageAttachment;

public record MessageAttachmentDto(
        String url,
        String thumbnailUrl,
        ChatAttachmentType type,
        String contentType,
        String originalName,
        long sizeBytes,
        Integer width,
        Integer height,
        Double durationSeconds,
        int sortOrder
) {
    public static MessageAttachmentDto fromEntity(MessageAttachment attachment, int sortOrder) {
        return new MessageAttachmentDto(
                attachment.getUrl(),
                attachment.getThumbnailUrl(),
                attachment.getType(),
                attachment.getContentType(),
                attachment.getOriginalName(),
                attachment.getSizeBytes(),
                attachment.getWidth(),
                attachment.getHeight(),
                attachment.getDurationSeconds(),
                sortOrder
        );
    }
}
