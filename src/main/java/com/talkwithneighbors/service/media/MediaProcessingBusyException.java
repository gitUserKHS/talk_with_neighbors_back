package com.talkwithneighbors.service.media;

public class MediaProcessingBusyException extends RuntimeException {
    public MediaProcessingBusyException(String message) {
        super(message);
    }
}
