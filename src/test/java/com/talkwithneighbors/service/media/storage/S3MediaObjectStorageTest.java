package com.talkwithneighbors.service.media.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3MediaObjectStorageTest {

    @TempDir
    Path tempDirectory;

    @Mock
    S3Client s3Client;

    private S3MediaObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3MediaObjectStorage(s3Client, "private-media-bucket", "/neighbors/media/");
    }

    @Test
    void storesPrivateObjectUnderConfiguredPrefixWithContentMetadata() throws Exception {
        Path source = Files.write(tempDirectory.resolve("asset.jpg"), new byte[] {1, 2, 3});
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());

        storage.store("feed/asset.jpg", source, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("private-media-bucket");
        assertThat(request.getValue().key()).isEqualTo("neighbors/media/feed/asset.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().cacheControl()).contains("immutable");
        assertThat(body.getValue().optionalContentLength()).contains(3L);
    }

    @Test
    void deletesOwnedObjectAndChecksBucketHealthWithBoundedTimeouts() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());

        storage.delete("chat/file.pdf");
        storage.checkHealth();

        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo("neighbors/media/chat/file.pdf");
        assertThat(delete.getValue().overrideConfiguration()).isEmpty();

        ArgumentCaptor<HeadBucketRequest> health = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(s3Client).headBucket(health.capture());
        assertThat(health.getValue().bucket()).isEqualTo("private-media-bucket");
        assertThat(health.getValue().overrideConfiguration()).hasValueSatisfying(configuration -> {
            assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(3));
            assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
        });
    }

    @Test
    void readsMetadataAndStreamsOnlyTheRequestedRange() throws Exception {
        Instant modified = Instant.parse("2026-07-14T00:00:00Z");
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(4L)
                .contentType("video/mp4")
                .eTag("etag")
                .lastModified(modified)
                .build());
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[] {2, 3}))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        MediaObjectMetadata metadata = storage.metadata("feed/video.mp4");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        storage.writeTo("feed/video.mp4", "bytes=1-2", output);

        assertThat(metadata.contentLength()).isEqualTo(4);
        assertThat(metadata.contentType()).isEqualTo("video/mp4");
        assertThat(metadata.lastModified()).isEqualTo(modified);
        assertThat(output.toByteArray()).containsExactly(2, 3);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=1-2");
    }

    @Test
    void mapsMissingObjectToNotFoundWithoutLeakingAwsDetails() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("sensitive provider detail").build());

        assertThatThrownBy(() -> storage.metadata("profile/missing.jpg"))
                .isInstanceOf(MediaObjectNotFoundException.class)
                .hasMessageNotContaining("sensitive provider detail");
    }

    @Test
    void rejectsUnownedOrTraversalKeysBeforeCallingS3() {
        assertThatThrownBy(() -> storage.delete("../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("feed/subdirectory/file.jpg"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
