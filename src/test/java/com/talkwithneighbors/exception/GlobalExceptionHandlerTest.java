package com.talkwithneighbors.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {
    @Test
    void maxUploadResponseExplainsVideoAndWholeRequestLimits() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(125L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(
                "한 파일은 최대 30MB(사진 10MB·동영상 30MB·일반 파일 25MB), "
                        + "첨부 요청 전체는 125MB를 넘을 수 없어요.",
                response.getBody().message()
        );
    }
}
