package com.talkwithneighbors.service.media.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@ConditionalOnProperty(name = "app.media.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalMediaObjectStorage implements MediaObjectStorage {

    private static final byte[] PROBE_CONTENT = {0};

    private final Path rootDirectory;

    public LocalMediaObjectStorage(
            @Value("${app.media.storage-directory:./uploads}") String storageDirectory
    ) {
        this.rootDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void store(String relativeKey, Path source, String contentType) {
        Path target = resolve(relativeKey);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException exception) {
            throw new MediaObjectStorageException("Could not store local media object", exception);
        }
    }

    @Override
    public void delete(String relativeKey) {
        Path target = resolve(relativeKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException | SecurityException exception) {
            throw new MediaObjectStorageException("Could not delete local media object", exception);
        }
    }

    @Override
    public void checkHealth() {
        Path probeFile = null;
        try {
            Files.createDirectories(rootDirectory);
            probeFile = Files.createTempFile(rootDirectory, ".readiness-", ".probe");
            Files.write(probeFile, PROBE_CONTENT);
            Files.delete(probeFile);
        } catch (IOException | SecurityException exception) {
            throw new MediaObjectStorageException("Local media storage is unavailable", exception);
        } finally {
            deleteProbeQuietly(probeFile);
        }
    }

    private Path resolve(String relativeKey) {
        String safeKey = MediaStoragePath.validateRelativeKey(relativeKey);
        Path target = rootDirectory.resolve(safeKey.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Invalid media object key");
        }
        return target;
    }

    private void deleteProbeQuietly(Path probeFile) {
        if (probeFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(probeFile);
        } catch (IOException | SecurityException ignored) {
            // The original health failure is retained.
        }
    }
}
