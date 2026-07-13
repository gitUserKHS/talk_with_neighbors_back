package com.talkwithneighbors.service.media.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;

@Component
@ConditionalOnProperty(name = "app.media.storage-type", havingValue = "s3")
public class S3MediaObjectStorage implements MediaObjectStorage {

    private static final String OBJECT_CACHE_CONTROL = "public, max-age=2592000, immutable";

    private final S3Client s3Client;
    private final String bucket;
    private final String prefix;

    public S3MediaObjectStorage(
            S3Client s3Client,
            @Value("${app.media.s3.bucket}") String bucket,
            @Value("${app.media.s3.prefix:media}") String prefix
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.media.s3.bucket is required when S3 media storage is enabled");
        }
        this.s3Client = s3Client;
        this.bucket = bucket.trim();
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public void store(String relativeKey, Path source, String contentType) {
        String objectKey = objectKey(relativeKey);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(safeContentType(contentType))
                .cacheControl(OBJECT_CACHE_CONTROL)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromFile(source));
        } catch (RuntimeException exception) {
            throw new MediaObjectStorageException("Could not store S3 media object", exception);
        }
    }

    @Override
    public void delete(String relativeKey) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(relativeKey))
                .build();
        try {
            s3Client.deleteObject(request);
        } catch (RuntimeException exception) {
            throw new MediaObjectStorageException("Could not delete S3 media object", exception);
        }
    }

    @Override
    public void checkHealth() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException exception) {
            throw new MediaObjectStorageException("S3 media storage is unavailable", exception);
        }
    }

    public MediaObjectMetadata metadata(String relativeKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(relativeKey))
                    .build());
            Long contentLength = response.contentLength();
            return new MediaObjectMetadata(
                    contentLength == null ? 0 : contentLength,
                    safeContentType(response.contentType()),
                    response.eTag(),
                    response.lastModified()
            );
        } catch (NoSuchKeyException exception) {
            throw new MediaObjectNotFoundException();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MediaObjectNotFoundException();
            }
            throw new MediaObjectStorageException("Could not read S3 media metadata", exception);
        } catch (RuntimeException exception) {
            throw new MediaObjectStorageException("Could not read S3 media metadata", exception);
        }
    }

    public void writeTo(String relativeKey, String range, OutputStream outputStream) throws IOException {
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(relativeKey));
        if (range != null && !range.isBlank()) {
            request.range(range);
        }
        try (ResponseInputStream<?> input = s3Client.getObject(request.build())) {
            input.transferTo(outputStream);
        } catch (NoSuchKeyException exception) {
            throw new MediaObjectNotFoundException();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MediaObjectNotFoundException();
            }
            throw new MediaObjectStorageException("Could not stream S3 media object", exception);
        } catch (SdkException exception) {
            throw new MediaObjectStorageException("Could not stream S3 media object", exception);
        }
    }

    String objectKey(String relativeKey) {
        String safeKey = MediaStoragePath.validateRelativeKey(relativeKey);
        return prefix.isEmpty() ? safeKey : prefix + "/" + safeKey;
    }

    private String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("//")
                || Arrays.stream(normalized.split("/", -1))
                .anyMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment))) {
            throw new IllegalArgumentException("Invalid S3 media prefix");
        }
        return normalized;
    }
}
