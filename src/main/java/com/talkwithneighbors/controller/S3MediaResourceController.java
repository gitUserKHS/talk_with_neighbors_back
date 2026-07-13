package com.talkwithneighbors.controller;

import com.talkwithneighbors.service.media.storage.MediaObjectMetadata;
import com.talkwithneighbors.service.media.storage.MediaObjectNotFoundException;
import com.talkwithneighbors.service.media.storage.MediaObjectStorageException;
import com.talkwithneighbors.service.media.storage.MediaStoragePath;
import com.talkwithneighbors.service.media.storage.S3MediaObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

@RestController
@ConditionalOnProperty(name = "app.media.storage-type", havingValue = "s3")
public class S3MediaResourceController {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final S3MediaObjectStorage storage;

    public S3MediaResourceController(S3MediaObjectStorage storage) {
        this.storage = storage;
    }

    @RequestMapping(
            path = "/uploads/{category}/{fileName:.+}",
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    public ResponseEntity<StreamingResponseBody> resource(
            @PathVariable String category,
            @PathVariable String fileName,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request
    ) {
        String relativeKey;
        try {
            relativeKey = MediaStoragePath.relativeKey(category, fileName);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }

        MediaObjectMetadata metadata;
        try {
            metadata = storage.metadata(relativeKey);
        } catch (MediaObjectNotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (MediaObjectStorageException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media storage is unavailable");
        }

        ByteRange range;
        try {
            range = ByteRange.parse(rangeHeader, metadata.contentLength());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + metadata.contentLength())
                    .build();
        }

        HttpHeaders headers = responseHeaders(metadata, range);
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            return new ResponseEntity<>(null, headers, range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT);
        }

        String s3Range = range == null ? null : range.asHeaderValue();
        StreamingResponseBody body = outputStream -> storage.writeTo(relativeKey, s3Range, outputStream);
        return new ResponseEntity<>(body, headers, range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT);
    }

    private HttpHeaders responseHeaders(MediaObjectMetadata metadata, ByteRange range) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(metadata.contentType()));
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(X_CONTENT_TYPE_OPTIONS, "nosniff");
        if (metadata.eTag() != null && !metadata.eTag().isBlank()) {
            String eTag = metadata.eTag().startsWith("\"")
                    ? metadata.eTag()
                    : "\"" + metadata.eTag() + "\"";
            headers.setETag(eTag);
        }
        if (metadata.lastModified() != null) {
            headers.setLastModified(metadata.lastModified().toEpochMilli());
        }
        if (range == null) {
            headers.setContentLength(metadata.contentLength());
        } else {
            headers.setContentLength(range.length());
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + metadata.contentLength());
        }
        return headers;
    }

    private MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private record ByteRange(long start, long end) {

        private static ByteRange parse(String header, long totalLength) {
            if (header == null || header.isBlank()) {
                return null;
            }
            String value = header.trim();
            if (!value.regionMatches(true, 0, "bytes=", 0, 6) || value.indexOf(',') >= 0 || totalLength <= 0) {
                throw new IllegalArgumentException("Unsupported range");
            }
            String specification = value.substring(6).trim();
            int dash = specification.indexOf('-');
            if (dash < 0 || dash != specification.lastIndexOf('-')) {
                throw new IllegalArgumentException("Invalid range");
            }
            String startValue = specification.substring(0, dash).trim();
            String endValue = specification.substring(dash + 1).trim();
            try {
                if (startValue.isEmpty()) {
                    long suffixLength = Long.parseLong(endValue);
                    if (suffixLength <= 0) {
                        throw new IllegalArgumentException("Invalid suffix range");
                    }
                    long start = Math.max(0, totalLength - suffixLength);
                    return new ByteRange(start, totalLength - 1);
                }
                long start = Long.parseLong(startValue);
                if (start < 0 || start >= totalLength) {
                    throw new IllegalArgumentException("Unsatisfiable range");
                }
                long end = endValue.isEmpty() ? totalLength - 1 : Long.parseLong(endValue);
                if (end < start) {
                    throw new IllegalArgumentException("Invalid range");
                }
                return new ByteRange(start, Math.min(end, totalLength - 1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid range", exception);
            }
        }

        private long length() {
            return end - start + 1;
        }

        private String asHeaderValue() {
            return "bytes=" + start + "-" + end;
        }
    }
}
