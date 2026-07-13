package com.talkwithneighbors.service;

import com.talkwithneighbors.entity.ChatAttachmentType;
import com.talkwithneighbors.entity.FeedMediaType;
import com.talkwithneighbors.entity.FeedPostMedia;
import com.talkwithneighbors.entity.MessageAttachment;
import com.talkwithneighbors.exception.MatchingException;
import com.talkwithneighbors.service.media.MediaAsset;
import com.talkwithneighbors.service.media.MediaAssetKind;
import com.talkwithneighbors.service.media.MediaProcessingException;
import com.talkwithneighbors.service.media.MediaProcessingBusyException;
import com.talkwithneighbors.service.media.MediaProcessingRequest;
import com.talkwithneighbors.service.media.MediaProcessor;
import com.talkwithneighbors.service.media.ProcessedMedia;
import com.talkwithneighbors.service.media.storage.LocalMediaObjectStorage;
import com.talkwithneighbors.service.media.storage.MediaObjectStorage;
import com.talkwithneighbors.service.media.storage.MediaObjectStorageException;
import com.talkwithneighbors.service.media.storage.MediaStoragePath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class MediaStorageService {
    public static final int MAX_MEDIA_COUNT = 10;
    public static final int MAX_CHAT_ATTACHMENT_COUNT = 5;
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    public static final long MAX_CHAT_TOTAL_BYTES = 120L * 1024 * 1024;

    private static final Set<String> ZIP_EXTENSIONS = Set.of(".zip", ".docx", ".xlsx", ".pptx");
    private static final Set<String> OLE_EXTENSIONS = Set.of(".doc", ".xls", ".ppt");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".csv", ".json", ".md");
    private static final Map<String, String> FILE_CONTENT_TYPES = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".zip", "application/zip"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".json", "application/json"),
            Map.entry(".md", "text/markdown")
    );

    private final Path rootDirectory;
    private final Path incomingDirectory;
    private final Path processingDirectory;
    private final MediaProcessor mediaProcessor;
    private final MediaObjectStorage objectStorage;

    @Autowired
    public MediaStorageService(
            @Value("${app.media.storage-directory:./uploads}") String storageDirectory,
            MediaProcessor mediaProcessor,
            MediaObjectStorage objectStorage
    ) {
        this.rootDirectory = Paths.get(storageDirectory).toAbsolutePath().normalize();
        this.incomingDirectory = rootDirectory.resolve(".incoming").normalize();
        this.processingDirectory = incomingDirectory.resolve("processed").normalize();
        this.mediaProcessor = mediaProcessor;
        this.objectStorage = objectStorage;
        createDirectories(rootDirectory, incomingDirectory, processingDirectory);
    }

    public MediaStorageService(String storageDirectory, MediaProcessor mediaProcessor) {
        this(storageDirectory, mediaProcessor, new LocalMediaObjectStorage(storageDirectory));
    }

    /** Lightweight constructor used by storage validation tests without requiring FFmpeg. */
    public MediaStorageService(String storageDirectory) {
        this(storageDirectory, new PassthroughMediaProcessor(), new LocalMediaObjectStorage(storageDirectory));
    }

    public List<FeedPostMedia> storePostMedia(List<MultipartFile> files) {
        List<MediaAsset> assets = storeAssets(files, new StoragePolicy(
                "feed", MAX_MEDIA_COUNT, MAX_TOTAL_BYTES, false, false, true, 1920));
        return assets.stream()
                .map(asset -> new FeedPostMedia(
                        asset.url(),
                        asset.type() == MediaAssetKind.IMAGE ? FeedMediaType.IMAGE : FeedMediaType.VIDEO,
                        asset.thumbnailUrl(),
                        asset.contentType(),
                        asset.sizeBytes(),
                        asset.width(),
                        asset.height(),
                        asset.durationSeconds()
                ))
                .toList();
    }

    public MediaAsset storeProfileImage(MultipartFile file) {
        List<MediaAsset> assets = storeAssets(List.of(file), new StoragePolicy(
                "profile", 1, MAX_IMAGE_BYTES, false, true, true, 1024));
        return assets.get(0);
    }

    public List<MessageAttachment> storeChatAttachments(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<MediaAsset> assets = storeAssets(files, new StoragePolicy(
                "chat", MAX_CHAT_ATTACHMENT_COUNT, MAX_CHAT_TOTAL_BYTES, true, false, false, 1920));
        return assets.stream()
                .map(asset -> new MessageAttachment(
                        asset.url(),
                        asset.thumbnailUrl(),
                        switch (asset.type()) {
                            case IMAGE -> ChatAttachmentType.IMAGE;
                            case VIDEO -> ChatAttachmentType.VIDEO;
                            case FILE -> ChatAttachmentType.FILE;
                        },
                        asset.contentType(),
                        asset.originalName(),
                        asset.sizeBytes(),
                        asset.width(),
                        asset.height(),
                        asset.durationSeconds()
                ))
                .toList();
    }

    public void deletePostMedia(Collection<String> urls) {
        deleteMedia(urls);
    }

    public void deleteMedia(Collection<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            String relativeKey = MediaStoragePath.fromPublicUrl(url).orElse(null);
            if (relativeKey == null) {
                continue;
            }
            try {
                objectStorage.delete(relativeKey);
            } catch (RuntimeException exception) {
                log.warn("Could not delete owned media object. key={}", relativeKey, exception);
            }
        }
    }

    public List<String> attachmentUrls(Collection<MessageAttachment> attachments) {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .flatMap(attachment -> Stream.of(attachment.getUrl(), attachment.getThumbnailUrl()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<MediaAsset> storeAssets(List<MultipartFile> files, StoragePolicy policy) {
        validateRequest(files, policy);
        Path outputDirectory = processingDirectory.resolve(policy.category()).normalize();
        if (!outputDirectory.startsWith(processingDirectory)) {
            throw new MatchingException("안전하지 않은 미디어 경로입니다.", HttpStatus.BAD_REQUEST);
        }
        createDirectories(outputDirectory);

        List<Path> storedPaths = new ArrayList<>();
        List<String> storedKeys = new ArrayList<>();
        List<MediaAsset> storedAssets = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                DetectedMedia detected = detect(file, policy.allowFiles());
                validateFileLimit(file, detected, policy);
                String originalName = safeOriginalName(file.getOriginalFilename());
                String baseName = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
                Path incoming = incomingDirectory.resolve(baseName + detected.extension()).normalize();
                if (!incoming.startsWith(incomingDirectory)) {
                    throw new MatchingException("안전하지 않은 임시 파일 경로입니다.", HttpStatus.BAD_REQUEST);
                }

                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, incoming, StandardCopyOption.REPLACE_EXISTING);
                }

                try {
                    MediaAsset asset;
                    if (detected.type() == MediaAssetKind.FILE) {
                        Path target = outputDirectory.resolve(baseName + detected.extension()).normalize();
                        Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
                        storedPaths.add(target);
                        String relativeKey = relativeKey(policy.category(), target);
                        long sizeBytes = Files.size(target);
                        persistObject(relativeKey, target, detected.contentType(), storedKeys);
                        asset = new MediaAsset(
                                MediaStoragePath.publicUrl(relativeKey),
                                null,
                                MediaAssetKind.FILE,
                                detected.contentType(),
                                originalName,
                                sizeBytes,
                                null,
                                null,
                                null
                        );
                    } else {
                        ProcessedMedia processed = mediaProcessor.process(new MediaProcessingRequest(
                                incoming,
                                outputDirectory,
                                baseName,
                                detected.type(),
                                detected.extension(),
                                !policy.profileImage(),
                                !policy.profileImage() && ".gif".equals(detected.extension()),
                                policy.maxDimension()
                        ));
                        storedPaths.add(processed.mediaPath());
                        if (processed.thumbnailPath() != null) {
                            storedPaths.add(processed.thumbnailPath());
                        }
                        String mediaKey = relativeKey(policy.category(), processed.mediaPath());
                        String thumbnailKey = processed.thumbnailPath() == null
                                ? null : relativeKey(policy.category(), processed.thumbnailPath());
                        long sizeBytes = Files.size(processed.mediaPath());
                        persistObject(mediaKey, processed.mediaPath(), processed.contentType(), storedKeys);
                        if (processed.thumbnailPath() != null) {
                            persistObject(thumbnailKey, processed.thumbnailPath(), "image/webp", storedKeys);
                        }
                        asset = new MediaAsset(
                                MediaStoragePath.publicUrl(mediaKey),
                                thumbnailKey == null ? null : MediaStoragePath.publicUrl(thumbnailKey),
                                detected.type(),
                                processed.contentType(),
                                originalName,
                                sizeBytes,
                                processed.width(),
                                processed.height(),
                                processed.durationSeconds()
                        );
                    }
                    storedAssets.add(asset);
                } finally {
                    Files.deleteIfExists(incoming);
                }
            }
            return List.copyOf(storedAssets);
        } catch (MediaProcessingBusyException exception) {
            rollback(storedPaths, storedKeys);
            throw new MatchingException(exception.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        } catch (MediaProcessingException exception) {
            rollback(storedPaths, storedKeys);
            throw new MatchingException(exception.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (MediaObjectStorageException exception) {
            rollback(storedPaths, storedKeys);
            throw new MatchingException(
                    "미디어 파일을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } catch (IOException exception) {
            rollback(storedPaths, storedKeys);
            throw new MatchingException(
                    "미디어 파일을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        } catch (RuntimeException exception) {
            rollback(storedPaths, storedKeys);
            throw exception;
        }
    }

    private void validateRequest(List<MultipartFile> files, StoragePolicy policy) {
        if (files == null || files.isEmpty()) {
            if (policy.required()) {
                throw new MatchingException("첨부할 파일을 선택해주세요.", HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (files.size() > policy.maxCount()) {
            throw new MatchingException(
                    "한 번에 첨부할 수 있는 파일은 최대 " + policy.maxCount() + "개입니다.",
                    HttpStatus.BAD_REQUEST
            );
        }
        long totalBytes = files.stream()
                .filter(file -> file != null)
                .mapToLong(MultipartFile::getSize)
                .sum();
        if (totalBytes > policy.maxTotalBytes()) {
            throw new MatchingException(
                    "첨부 파일 전체 크기가 " + humanLimit(policy.maxTotalBytes()) + "를 넘을 수 없습니다.",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
    }

    private void validateFileLimit(MultipartFile file, DetectedMedia detected, StoragePolicy policy) {
        if (policy.profileImage() && detected.type() != MediaAssetKind.IMAGE) {
            throw new MatchingException("프로필에는 이미지 파일만 사용할 수 있습니다.", HttpStatus.BAD_REQUEST);
        }
        long maxBytes = switch (detected.type()) {
            case IMAGE -> MAX_IMAGE_BYTES;
            case VIDEO -> MAX_VIDEO_BYTES;
            case FILE -> MAX_FILE_BYTES;
        };
        if (file.getSize() > maxBytes) {
            throw new MatchingException(
                    detected.typeLabel() + " 한 개의 크기는 " + humanLimit(maxBytes) + "를 넘을 수 없습니다.",
                    HttpStatus.PAYLOAD_TOO_LARGE
            );
        }
    }

    private DetectedMedia detect(MultipartFile file, boolean allowFiles) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new MatchingException("비어 있는 파일은 첨부할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(512);
        }

        if (startsWith(header, 0xff, 0xd8, 0xff)) {
            return new DetectedMedia(MediaAssetKind.IMAGE, ".jpg", "image/jpeg");
        }
        if (startsWith(header, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return new DetectedMedia(MediaAssetKind.IMAGE, ".png", "image/png");
        }
        if (startsWithAscii(header, 0, "GIF87a") || startsWithAscii(header, 0, "GIF89a")) {
            return new DetectedMedia(MediaAssetKind.IMAGE, ".gif", "image/gif");
        }
        if (startsWithAscii(header, 0, "RIFF") && startsWithAscii(header, 8, "WEBP")) {
            return new DetectedMedia(MediaAssetKind.IMAGE, ".webp", "image/webp");
        }
        if (startsWithAscii(header, 4, "ftyp")) {
            String claimedType = file.getContentType();
            String extension = "video/quicktime".equalsIgnoreCase(claimedType) ? ".mov" : ".mp4";
            return new DetectedMedia(MediaAssetKind.VIDEO, extension, claimedTypeOr(claimedType, "video/mp4"));
        }
        if (startsWith(header, 0x1a, 0x45, 0xdf, 0xa3)) {
            return new DetectedMedia(MediaAssetKind.VIDEO, ".webm", "video/webm");
        }

        if (allowFiles) {
            DetectedMedia document = detectDocument(file, header);
            if (document != null) {
                return document;
            }
        }

        String supported = allowFiles
                ? "JPG, PNG, GIF, WebP, MP4, WebM, MOV, PDF, ZIP, Office, TXT, CSV, JSON, Markdown"
                : "JPG, PNG, GIF, WebP, MP4, WebM, MOV";
        throw new MatchingException("지원하지 않거나 내용이 일치하지 않는 파일입니다. 지원 형식: " + supported,
                HttpStatus.BAD_REQUEST);
    }

    private DetectedMedia detectDocument(MultipartFile file, byte[] header) {
        String extension = extensionOf(file.getOriginalFilename());
        if (".pdf".equals(extension) && startsWithAscii(header, 0, "%PDF-")) {
            return new DetectedMedia(MediaAssetKind.FILE, extension, FILE_CONTENT_TYPES.get(extension));
        }
        if (ZIP_EXTENSIONS.contains(extension)
                && (startsWith(header, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(header, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(header, 0x50, 0x4b, 0x07, 0x08))) {
            return new DetectedMedia(MediaAssetKind.FILE, extension, FILE_CONTENT_TYPES.get(extension));
        }
        if (OLE_EXTENSIONS.contains(extension)
                && startsWith(header, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) {
            return new DetectedMedia(MediaAssetKind.FILE, extension, FILE_CONTENT_TYPES.get(extension));
        }
        if (TEXT_EXTENSIONS.contains(extension) && looksLikeText(header)) {
            return new DetectedMedia(MediaAssetKind.FILE, extension, FILE_CONTENT_TYPES.get(extension));
        }
        return null;
    }

    private boolean looksLikeText(byte[] bytes) {
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned == 0) {
                return false;
            }
            if (unsigned < 0x09 || (unsigned > 0x0d && unsigned < 0x20)) {
                return false;
            }
        }
        return true;
    }

    private String safeOriginalName(String originalName) {
        String fallback = "attachment";
        if (originalName == null || originalName.isBlank()) {
            return fallback;
        }
        String fileName;
        try {
            fileName = Paths.get(originalName).getFileName().toString();
        } catch (RuntimeException ignored) {
            fileName = fallback;
        }
        fileName = fileName.replaceAll("[\\p{Cntrl}]", "").trim();
        if (fileName.isBlank()) {
            return fallback;
        }
        return fileName.length() <= 180 ? fileName : fileName.substring(fileName.length() - 180);
    }

    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        String normalized = originalName.toLowerCase(Locale.ROOT).trim();
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0 || lastDot == normalized.length() - 1) {
            return "";
        }
        String extension = normalized.substring(lastDot);
        return extension.length() <= 8 ? extension : "";
    }

    private String relativeKey(String category, Path path) {
        return MediaStoragePath.relativeKey(category, path.getFileName().toString());
    }

    private String claimedTypeOr(String claimed, String fallback) {
        return claimed == null || claimed.isBlank() ? fallback : claimed;
    }

    private boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xff) != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithAscii(byte[] bytes, int offset, String signature) {
        if (bytes.length < offset + signature.length()) {
            return false;
        }
        for (int index = 0; index < signature.length(); index++) {
            if ((char) bytes[offset + index] != signature.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private String humanLimit(long bytes) {
        return (bytes / 1024 / 1024) + "MB";
    }

    private void createDirectories(Path... directories) {
        try {
            for (Path directory : directories) {
                Files.createDirectories(directory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("미디어 저장 폴더를 만들 수 없습니다.", exception);
        }
    }

    private void deletePaths(Collection<Path> paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                log.warn("실패한 업로드의 임시 파일을 삭제하지 못했습니다. path={}", path, exception);
            }
        }
    }

    private void persistObject(
            String relativeKey,
            Path source,
            String contentType,
            Collection<String> storedKeys
    ) throws IOException {
        objectStorage.store(relativeKey, source, contentType);
        storedKeys.add(relativeKey);
        Files.deleteIfExists(source);
    }

    private void rollback(Collection<Path> paths, Collection<String> storedKeys) {
        deletePaths(paths);
        for (String relativeKey : storedKeys) {
            try {
                objectStorage.delete(relativeKey);
            } catch (RuntimeException exception) {
                log.warn("Could not roll back media object. key={}", relativeKey, exception);
            }
        }
    }

    private record StoragePolicy(
            String category,
            int maxCount,
            long maxTotalBytes,
            boolean allowFiles,
            boolean profileImage,
            boolean required,
            int maxDimension
    ) {
    }

    private record DetectedMedia(MediaAssetKind type, String extension, String contentType) {
        private String typeLabel() {
            return switch (type) {
                case IMAGE -> "이미지";
                case VIDEO -> "동영상";
                case FILE -> "파일";
            };
        }
    }

    private static final class PassthroughMediaProcessor implements MediaProcessor {
        @Override
        public ProcessedMedia process(MediaProcessingRequest request) throws IOException {
            String extension = request.sourceExtension();
            Path target = request.outputDirectory().resolve(request.baseName() + extension);
            Files.move(request.input(), target, StandardCopyOption.REPLACE_EXISTING);
            String contentType = request.type() == MediaAssetKind.IMAGE
                    ? imageContentType(extension)
                    : videoContentType(extension);
            return new ProcessedMedia(target, null, contentType, null, null, null);
        }

        private String imageContentType(String extension) {
            return switch (extension) {
                case ".png" -> "image/png";
                case ".gif" -> "image/gif";
                case ".webp" -> "image/webp";
                default -> "image/jpeg";
            };
        }

        private String videoContentType(String extension) {
            return ".webm".equals(extension) ? "video/webm" : "video/mp4";
        }
    }
}
