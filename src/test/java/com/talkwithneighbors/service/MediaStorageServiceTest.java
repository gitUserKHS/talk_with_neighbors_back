package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.service.media.MediaAssetKind;
import com.talkwithneighbors.service.media.MediaProcessor;
import com.talkwithneighbors.service.media.ProcessedMedia;
import com.talkwithneighbors.service.media.storage.MediaObjectStorage;
import com.talkwithneighbors.exception.MatchingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStorageServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void storesImageAndVideoInSelectionOrder() {
        MediaStorageService service = new MediaStorageService(tempDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        );
        MockMultipartFile video = new MockMultipartFile(
                "files", "clip.mp4", "video/mp4", mp4Bytes()
        );

        List<FeedPostMedia> stored = service.storePostMedia(List.of(image, video));

        assertEquals(2, stored.size());
        assertEquals(FeedMediaType.IMAGE, stored.get(0).getType());
        assertEquals(FeedMediaType.VIDEO, stored.get(1).getType());
        assertTrue(Files.exists(resolve(stored.get(0).getUrl())));
        assertTrue(Files.exists(resolve(stored.get(1).getUrl())));
    }

    @Test
    void rejectsSpoofedFileAndRemovesFilesStoredEarlierInTheRequest() throws IOException {
        MediaStorageService service = new MediaStorageService(tempDirectory.toString());
        MockMultipartFile image = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        );
        MockMultipartFile spoofed = new MockMultipartFile(
                "files", "not-really-video.mp4", "video/mp4", "plain text".getBytes()
        );

        assertThrows(MatchingException.class, () -> service.storePostMedia(List.of(image, spoofed)));

        try (var files = Files.list(tempDirectory.resolve("feed"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void deletesOnlyFilesOwnedByTheLocalMediaStore() {
        MediaStorageService service = new MediaStorageService(tempDirectory.toString());
        FeedPostMedia stored = service.storePostMedia(List.of(new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        ))).get(0);
        Path storedPath = resolve(stored.getUrl());
        assertTrue(Files.exists(storedPath));

        service.deletePostMedia(List.of(stored.getUrl(), "https://example.test/remote.jpg"));

        assertFalse(Files.exists(storedPath));
    }

    @Test
    void storesProcessedChatImageVideoAndSafeDocumentMetadata() throws IOException {
        MediaStorageService service = new MediaStorageService(tempDirectory.toString(), request -> {
            String extension = request.type() == MediaAssetKind.IMAGE ? ".webp" : ".mp4";
            Path media = request.outputDirectory().resolve(request.baseName() + extension);
            Path thumbnail = request.outputDirectory().resolve(request.baseName() + "-thumbnail.webp");
            Files.move(request.input(), media, StandardCopyOption.REPLACE_EXISTING);
            Files.write(thumbnail, new byte[] {1, 2, 3});
            return new ProcessedMedia(
                    media,
                    thumbnail,
                    request.type() == MediaAssetKind.IMAGE ? "image/webp" : "video/mp4",
                    640,
                    360,
                    request.type() == MediaAssetKind.VIDEO ? 1.25 : null
            );
        });

        var attachments = service.storeChatAttachments(List.of(
                new MockMultipartFile("files", "photo.jpg", "image/jpeg", jpegBytes()),
                new MockMultipartFile("files", "clip.mp4", "video/mp4", mp4Bytes()),
                new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes())
        ));

        assertEquals(3, attachments.size());
        assertEquals(ChatAttachmentType.IMAGE, attachments.get(0).getType());
        assertEquals("image/webp", attachments.get(0).getContentType());
        assertTrue(attachments.get(0).getThumbnailUrl().endsWith("-thumbnail.webp"));
        assertEquals(ChatAttachmentType.VIDEO, attachments.get(1).getType());
        assertEquals(1.25, attachments.get(1).getDurationSeconds());
        assertEquals(ChatAttachmentType.FILE, attachments.get(2).getType());
        assertEquals("notes.txt", attachments.get(2).getOriginalName());
    }

    @Test
    void rejectsVideoAsProfileImage() {
        MediaStorageService service = new MediaStorageService(tempDirectory.toString());
        MockMultipartFile video = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", mp4Bytes()
        );

        assertThrows(MatchingException.class, () -> service.storeProfileImage(video));
    }

    @Test
    void storesProcessedObjectsInRemoteStorageButKeepsStablePublicUrls() throws IOException {
        RecordingObjectStorage remoteStorage = new RecordingObjectStorage();
        MediaStorageService service = new MediaStorageService(
                tempDirectory.toString(), processorWithThumbnail(), remoteStorage);

        FeedPostMedia stored = service.storePostMedia(List.of(new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        ))).get(0);

        String mediaKey = stored.getUrl().substring("/uploads/".length());
        String thumbnailKey = stored.getThumbnailUrl().substring("/uploads/".length());
        assertTrue(stored.getUrl().startsWith("/uploads/feed/"));
        assertTrue(remoteStorage.objects.containsKey(mediaKey));
        assertTrue(remoteStorage.objects.containsKey(thumbnailKey));
        try (var files = Files.walk(tempDirectory.resolve(".incoming"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rollsBackRemoteObjectsWhenLaterFileInSameRequestIsInvalid() {
        RecordingObjectStorage remoteStorage = new RecordingObjectStorage();
        MediaStorageService service = new MediaStorageService(
                tempDirectory.toString(), processorWithThumbnail(), remoteStorage);
        MockMultipartFile valid = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        );
        MockMultipartFile spoofed = new MockMultipartFile(
                "files", "not-really-video.mp4", "video/mp4", "plain text".getBytes()
        );

        assertThrows(MatchingException.class, () -> service.storePostMedia(List.of(valid, spoofed)));

        assertTrue(remoteStorage.objects.isEmpty());
        assertFalse(remoteStorage.deletedKeys.isEmpty());
    }

    @Test
    void deletesOnlyOwnedRemoteUploadUrls() {
        RecordingObjectStorage remoteStorage = new RecordingObjectStorage();
        MediaStorageService service = new MediaStorageService(
                tempDirectory.toString(), processorWithThumbnail(), remoteStorage);
        FeedPostMedia stored = service.storePostMedia(List.of(new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", jpegBytes()
        ))).get(0);

        service.deleteMedia(List.of(
                stored.getUrl(),
                stored.getThumbnailUrl(),
                "https://example.test/remote.jpg",
                "/uploads/feed/../secret"
        ));

        assertTrue(remoteStorage.objects.isEmpty());
        assertEquals(2, remoteStorage.deletedKeys.size());
    }

    @Test
    void durableDeletionPropagatesObjectStorageFailureForOutboxRetry() {
        MediaObjectStorage failingStorage = new MediaObjectStorage() {
            @Override
            public void store(String relativeKey, Path source, String contentType) {
                // No-op for this deletion-only test.
            }

            @Override
            public void delete(String relativeKey) {
                throw new IllegalStateException("temporary object storage failure");
            }

            @Override
            public void checkHealth() {
                // No-op for this deletion-only test.
            }
        };
        MediaStorageService service = new MediaStorageService(
                tempDirectory.toString(), processorWithThumbnail(), failingStorage);

        assertThrows(IllegalStateException.class,
                () -> service.deleteMediaOrThrow(List.of("/uploads/feed/image.webp")));
    }

    private Path resolve(String publicUrl) {
        return tempDirectory.resolve("feed").resolve(publicUrl.substring("/uploads/feed/".length()));
    }

    private byte[] jpegBytes() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x01};
    }

    private byte[] mp4Bytes() {
        return new byte[] {
                0x00, 0x00, 0x00, 0x18,
                'f', 't', 'y', 'p',
                'i', 's', 'o', 'm',
                0x00, 0x00, 0x00, 0x00
        };
    }

    private MediaProcessor processorWithThumbnail() {
        return request -> {
            String extension = request.type() == MediaAssetKind.IMAGE ? ".webp" : ".mp4";
            Path media = request.outputDirectory().resolve(request.baseName() + extension);
            Path thumbnail = request.outputDirectory().resolve(request.baseName() + "-thumbnail.webp");
            Files.move(request.input(), media, StandardCopyOption.REPLACE_EXISTING);
            Files.write(thumbnail, new byte[] {1, 2, 3});
            return new ProcessedMedia(media, thumbnail,
                    request.type() == MediaAssetKind.IMAGE ? "image/webp" : "video/mp4",
                    640, 360, null);
        };
    }

    private static final class RecordingObjectStorage implements MediaObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final java.util.Set<String> deletedKeys = new java.util.HashSet<>();

        @Override
        public void store(String relativeKey, Path source, String contentType) {
            try {
                objects.put(relativeKey, Files.readAllBytes(source));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void delete(String relativeKey) {
            deletedKeys.add(relativeKey);
            objects.remove(relativeKey);
        }

        @Override
        public void checkHealth() {
            // In-memory fake is always healthy.
        }
    }
}
