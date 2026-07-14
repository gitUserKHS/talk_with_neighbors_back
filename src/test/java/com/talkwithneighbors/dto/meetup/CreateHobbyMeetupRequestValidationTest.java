package com.talkwithneighbors.dto.meetup;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateHobbyMeetupRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsCoordinatesOnlyAsACompleteValidPair() {
        CreateHobbyMeetupRequest request = validRequest();
        request.setLatitude(37.5662968);

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("coordinatePairValid"));

        request.setLongitude(126.9779451);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsCoordinatesOutsideWgs84Ranges() {
        CreateHobbyMeetupRequest request = validRequest();
        request.setLatitude(91.0);
        request.setLongitude(-181.0);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("latitude", "longitude");
    }

    @Test
    void keepsLegacyTextOnlyLocationsValid() {
        CreateHobbyMeetupRequest request = validRequest();
        request.setLocation("서울도서관");
        request.setLocationAddress("서울 중구 세종대로 110");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void requiresAnExplicitOffsetAtTheJsonBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        String base = "{\"title\":\"저녁 산책\",\"interestTags\":[\"산책\"],\"maxParticipants\":6,";

        CreateHobbyMeetupRequest parsed = mapper.readValue(
                base + "\"scheduledAt\":\"2099-08-01T19:00:00+09:00\"}",
                CreateHobbyMeetupRequest.class
        );

        assertThat(parsed.getScheduledAt().getOffset().getTotalSeconds()).isEqualTo(9 * 60 * 60);
        assertThatThrownBy(() -> mapper.readValue(
                base + "\"scheduledAt\":\"2099-08-01T19:00:00\"}",
                CreateHobbyMeetupRequest.class
        )).hasMessageContaining("OffsetDateTime");
    }

    private CreateHobbyMeetupRequest validRequest() {
        CreateHobbyMeetupRequest request = new CreateHobbyMeetupRequest();
        request.setTitle("주말 독서 모임");
        request.setInterestTags(List.of("독서"));
        request.setMaxParticipants(6);
        return request;
    }
}
