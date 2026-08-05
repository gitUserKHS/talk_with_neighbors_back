package com.talkwithneighbors.dto.admin;

import java.time.LocalDateTime;

/**
 * 신고된 대상의 현재 내용. 운영자가 판단하려면 신고 사유만으로는 부족하다.
 *
 * @param available 대상이 이미 삭제되었으면 false. 이 경우 나머지 필드는 비어 있다.
 */
public record ReportedContentDto(
        boolean available,
        Long authorId,
        String authorUsername,
        String text,
        String imageUrl,
        LocalDateTime createdAt
) {
    public static ReportedContentDto missing() {
        return new ReportedContentDto(false, null, null, null, null, null);
    }
}
