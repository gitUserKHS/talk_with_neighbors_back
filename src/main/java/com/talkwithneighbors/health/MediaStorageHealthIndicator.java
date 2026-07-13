package com.talkwithneighbors.health;

import com.talkwithneighbors.service.media.storage.LocalMediaObjectStorage;
import com.talkwithneighbors.service.media.storage.MediaObjectStorage;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MediaStorageHealthIndicator implements HealthIndicator {

    private final MediaObjectStorage storage;

    @Autowired
    public MediaStorageHealthIndicator(
            MediaObjectStorage storage
    ) {
        this.storage = storage;
    }

    public MediaStorageHealthIndicator(String storageDirectory) {
        this(new LocalMediaObjectStorage(storageDirectory));
    }

    @Override
    public Health health() {
        try {
            storage.checkHealth();
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
