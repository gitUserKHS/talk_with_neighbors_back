package com.talkwithneighbors.service.media.storage;

public class MediaObjectNotFoundException extends MediaObjectStorageException {

    public MediaObjectNotFoundException() {
        super("Media object was not found");
    }
}
