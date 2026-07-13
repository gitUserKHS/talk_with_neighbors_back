package com.talkwithneighbors.health;

import com.talkwithneighbors.service.media.storage.MediaObjectStorage;
import com.talkwithneighbors.service.media.storage.MediaObjectStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MediaStorageHealthIndicatorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsStorageAndCompletesWriteDeleteProbe() throws IOException {
        Path storageDirectory = tempDirectory.resolve("uploads").resolve("media");
        var indicator = new MediaStorageHealthIndicator(storageDirectory.toString());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
        assertThat(storageDirectory).isDirectory();
        try (var files = Files.list(storageDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void returnsDownWithoutExposingPathOrExceptionDetails() throws IOException {
        Path regularFile = Files.writeString(tempDirectory.resolve("not-a-directory"), "occupied");
        var indicator = new MediaStorageHealthIndicator(regularFile.toString());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
        assertThat(health.toString()).doesNotContain(regularFile.toString());
    }

    @Test
    void delegatesRemoteHealthCheckWithoutExposingProviderFailure() {
        MediaObjectStorage storage = mock(MediaObjectStorage.class);
        doThrow(new MediaObjectStorageException("secret bucket/provider detail"))
                .when(storage).checkHealth();
        var indicator = new MediaStorageHealthIndicator(storage);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
        assertThat(health.toString()).doesNotContain("secret bucket/provider detail");
        verify(storage).checkHealth();
    }
}
