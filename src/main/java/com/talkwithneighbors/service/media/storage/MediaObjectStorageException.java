package com.talkwithneighbors.service.media.storage;

public class MediaObjectStorageException extends RuntimeException {

    public MediaObjectStorageException(String message) {
        super(message);
    }

    public MediaObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
