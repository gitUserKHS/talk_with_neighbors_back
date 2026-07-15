package com.talkwithneighbors.dto.schedule;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record UpdateChatScheduleRequest(
        @NotNull(message = "일정 버전이 필요해요.") Long version,
        @Size(max = 80, message = "일정 이름은 80자 이하여야 해요.") String title,
        @Size(max = 500, message = "일정 설명은 500자 이하여야 해요.") String description,
        @Future(message = "일정 시작 시간은 현재 이후여야 해요.") OffsetDateTime startsAt,
        @Min(value = 30, message = "일정 시간은 30분 이상이어야 해요.")
        @Max(value = 1440, message = "일정 시간은 하루를 넘길 수 없어요.") Integer durationMinutes,
        @Size(max = 64, message = "시간대 값이 너무 길어요.") String timeZone,
        @Size(max = 100, message = "장소 이름은 100자 이하여야 해요.") String location,
        @Size(max = 255, message = "장소 주소는 255자 이하여야 해요.") String locationAddress,
        @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 해요.")
        @DecimalMax(value = "90.0", message = "위도는 90 이하여야 해요.") Double latitude,
        @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 해요.")
        @DecimalMax(value = "180.0", message = "경도는 180 이하여야 해요.") Double longitude,
        @Size(max = 64, message = "카카오 장소 ID는 64자 이하여야 해요.") String kakaoPlaceId
) {
    @AssertTrue(message = "위도와 경도는 함께 입력해야 해요.")
    public boolean isCoordinatePairValid() {
        if (latitude == null && longitude == null) {
            return true;
        }
        return latitude != null && longitude != null;
    }

    @AssertTrue(message = "올바른 IANA 시간대를 입력해 주세요.")
    public boolean isTimeZoneValid() {
        if (timeZone == null || timeZone.isBlank()) {
            return true;
        }
        try {
            ZoneId.of(timeZone);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
