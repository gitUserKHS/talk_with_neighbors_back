package com.talkwithneighbors.dto.schedule;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatScheduleRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresDurationAndAnIanaTimeZone() {
        CreateChatScheduleRequest request = new CreateChatScheduleRequest(
                "산책",
                null,
                OffsetDateTime.now().plusDays(1),
                null,
                "Seoul",
                null,
                null,
                null,
                null,
                null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("durationMinutes", "timeZoneValid");
    }

    @Test
    void coordinatesMustBeACompleteWgs84Pair() {
        CreateChatScheduleRequest request = new CreateChatScheduleRequest(
                "산책",
                null,
                OffsetDateTime.now().plusDays(1),
                60,
                "Asia/Seoul",
                "서울숲",
                null,
                37.5,
                null,
                null);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("coordinatePairValid");
    }

    @Test
    void acceptsOffsetTimestampAndKakaoLocation() {
        CreateChatScheduleRequest request = new CreateChatScheduleRequest(
                "산책",
                "같이 걸어요",
                OffsetDateTime.now().plusDays(1),
                60,
                "Asia/Seoul",
                "서울숲",
                "서울 성동구 뚝섬로 273",
                37.5444,
                127.0374,
                "place-1");

        assertThat(validator.validate(request)).isEmpty();
    }
}
