package com.talkwithneighbors.service;

import com.talkwithneighbors.service.media.storage.MediaObjectStorage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Publishes repository-owned artwork under deterministic media keys. */
@Component
public class OfficialContentMediaPublisher {
    private static final List<Asset> ASSETS = List.of(
            new Asset(OfficialContentSeedService.WALK_IMAGE_KEY, "official-content/evening-walk.svg"),
            new Asset(OfficialContentSeedService.MAP_IMAGE_KEY, "official-content/map-meetup.svg"),
            new Asset(OfficialContentSeedService.SAFETY_IMAGE_KEY, "official-content/safe-neighbors.svg")
    );

    private final MediaObjectStorage storage;

    public OfficialContentMediaPublisher(MediaObjectStorage storage) {
        this.storage = storage;
    }

    public void publish() {
        for (Asset asset : ASSETS) {
            publish(asset);
        }
    }

    private void publish(Asset asset) {
        ClassPathResource resource = new ClassPathResource(asset.classpathLocation());
        Path temporaryFile = null;
        try (InputStream input = resource.getInputStream()) {
            temporaryFile = Files.createTempFile("official-content-", ".svg");
            Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            storage.store(asset.storageKey(), temporaryFile, "image/svg+xml");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not publish official content artwork: " + asset.storageKey(), exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The storage exception remains the useful failure signal.
                }
            }
        }
    }

    private record Asset(String storageKey, String classpathLocation) {
    }
}
