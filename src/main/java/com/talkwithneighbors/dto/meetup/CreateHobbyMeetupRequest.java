package com.talkwithneighbors.dto.meetup;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.time.OffsetDateTime;

@Data
public class CreateHobbyMeetupRequest {
    @NotBlank(message = "모임 이름을 입력해 주세요.")
    @Size(max = 80, message = "모임 이름은 80자 이하여야 해요.")
    private String title;

    @Size(max = 500, message = "모임 소개는 500자 이하여야 해요.")
    private String description;

    @NotEmpty(message = "관심사 태그를 하나 이상 입력해 주세요.")
    @Size(max = 5, message = "관심사 태그는 최대 5개까지 입력할 수 있어요.")
    private List<@NotBlank(message = "빈 관심사 태그는 사용할 수 없어요.") @Size(max = 30) String> interestTags;

    @Size(max = 100, message = "모임 장소는 100자 이하여야 해요.")
    private String location;

    @Size(max = 255, message = "모임 장소 주소는 255자 이하여야 해요.")
    private String locationAddress;

    @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 해요.")
    @DecimalMax(value = "90.0", message = "위도는 90 이하여야 해요.")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 해요.")
    @DecimalMax(value = "180.0", message = "경도는 180 이하여야 해요.")
    private Double longitude;

    @Size(max = 64, message = "카카오 장소 ID는 64자 이하여야 해요.")
    private String kakaoPlaceId;

    @NotNull(message = "모집 인원을 입력해 주세요.")
    @Min(value = 2, message = "모집 인원은 2명 이상이어야 해요.")
    @Max(value = 50, message = "모집 인원은 50명 이하여야 해요.")
    private Integer maxParticipants;

    /**
     * ISO-8601 timestamp with an explicit offset, for example
     * {@code 2026-07-18T19:00:00+09:00}.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime scheduledAt;

    @Min(value = 30, message = "모임 시간은 30분 이상이어야 해요.")
    @Max(value = 1440, message = "모임 시간은 하루를 넘길 수 없어요.")
    private Integer durationMinutes;

    /** ISO-8601 timestamp with an explicit offset. */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private OffsetDateTime registrationDeadline;

    @AssertTrue(message = "위도와 경도는 함께 입력해야 해요.")
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }
}
