package com.talkwithneighbors.service.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

@Component
@Slf4j
public class FfmpegMediaProcessor implements MediaProcessor {
    private static final int THUMBNAIL_MAX_DIMENSION = 480;

    private final String ffmpegCommand;
    private final String ffprobeCommand;
    private final Duration timeout;
    private final double maxInputVideoDurationSeconds;
    private final long maxInputVideoPixels;
    private final int maxInputVideoDimension;
    private final Semaphore processingSlots;
    private final ProcessStarter processStarter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FfmpegMediaProcessor(
            @Value("${app.media.ffmpeg-command:ffmpeg}") String ffmpegCommand,
            @Value("${app.media.ffprobe-command:ffprobe}") String ffprobeCommand,
            @Value("${app.media.processing-timeout-seconds:90}") long timeoutSeconds,
            @Value("${app.media.max-concurrent-processes:1}") int maxConcurrentProcesses,
            @Value("${app.media.max-video-duration-seconds:60}") double maxInputVideoDurationSeconds,
            @Value("${app.media.max-video-pixels:2073600}") long maxInputVideoPixels,
            @Value("${app.media.max-video-dimension:1920}") int maxInputVideoDimension
    ) {
        this(
                ffmpegCommand,
                ffprobeCommand,
                timeoutSeconds,
                maxConcurrentProcesses,
                maxInputVideoDurationSeconds,
                maxInputVideoPixels,
                maxInputVideoDimension,
                command -> new ProcessBuilder(command).redirectErrorStream(true).start()
        );
    }

    FfmpegMediaProcessor(
            String ffmpegCommand,
            String ffprobeCommand,
            long timeoutSeconds,
            int maxConcurrentProcesses,
            double maxInputVideoDurationSeconds,
            long maxInputVideoPixels,
            int maxInputVideoDimension,
            ProcessStarter processStarter
    ) {
        this.ffmpegCommand = ffmpegCommand;
        this.ffprobeCommand = ffprobeCommand;
        this.timeout = Duration.ofSeconds(Math.max(10, Math.min(timeoutSeconds, 3600)));
        if (!Double.isFinite(maxInputVideoDurationSeconds) || maxInputVideoDurationSeconds < 1) {
            throw new IllegalArgumentException("app.media.max-video-duration-seconds must be a positive number");
        }
        this.maxInputVideoDurationSeconds = maxInputVideoDurationSeconds;
        this.maxInputVideoPixels = Math.max(1, maxInputVideoPixels);
        this.maxInputVideoDimension = Math.max(1, maxInputVideoDimension);
        this.processingSlots = new Semaphore(Math.max(1, maxConcurrentProcesses), true);
        this.processStarter = processStarter;
    }

    @Override
    public ProcessedMedia process(MediaProcessingRequest request) throws IOException {
        Files.createDirectories(request.outputDirectory());
        boolean acquired;
        try {
            acquired = processingSlots.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaProcessingBusyException("미디어 변환 대기가 중단되었습니다.");
        }
        if (!acquired) {
            throw new MediaProcessingBusyException("미디어 변환 요청이 많습니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            return switch (request.type()) {
                case IMAGE -> processImage(request, deadlineNanos);
                case VIDEO -> processVideo(request, deadlineNanos);
                case FILE -> throw new IllegalArgumentException("Files do not require FFmpeg processing.");
            };
        } finally {
            processingSlots.release();
        }
    }

    private ProcessedMedia processImage(MediaProcessingRequest request, long deadlineNanos) throws IOException {
        boolean keepGif = request.preserveAnimation() && ".gif".equals(request.sourceExtension());
        Path mediaPath = request.outputDirectory().resolve(
                request.baseName() + (keepGif ? ".gif" : ".webp"));
        Path thumbnailPath = request.generateThumbnail()
                ? request.outputDirectory().resolve(request.baseName() + "-thumbnail.webp")
                : null;

        try {
            if (keepGif) {
                Files.move(request.input(), mediaPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                runFfmpeg(List.of(
                        "-threads", "1",
                        "-i", request.input().toString(),
                        "-vf", boundedScale(request.maxDimension()),
                        "-frames:v", "1",
                        "-c:v", "libwebp",
                        "-threads:v", "1",
                        "-quality", "82",
                        "-compression_level", "4",
                        mediaPath.toString()
                ), deadlineNanos);
            }

            requireNonEmptyOutput(mediaPath);

            if (thumbnailPath != null) {
                // Seeking into a still image can make FFmpeg exit successfully while
                // producing an empty file, so image thumbnails always use frame zero.
                runThumbnail(mediaPath, thumbnailPath, false, deadlineNanos);
                requireNonEmptyOutput(thumbnailPath);
            }
            ProbeResult probe = probeProcessedMedia(mediaPath, deadlineNanos);
            return new ProcessedMedia(
                    mediaPath,
                    thumbnailPath,
                    keepGif ? "image/gif" : "image/webp",
                    probe.width(),
                    probe.height(),
                    null
            );
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(mediaPath);
            deleteQuietly(thumbnailPath);
            throw exception;
        }
    }

    private ProcessedMedia processVideo(MediaProcessingRequest request, long deadlineNanos) throws IOException {
        Path mediaPath = request.outputDirectory().resolve(request.baseName() + ".mp4");
        Path thumbnailPath = request.generateThumbnail()
                ? request.outputDirectory().resolve(request.baseName() + "-thumbnail.webp")
                : null;

        try {
            ProbeResult inputProbe = probeRequiredVideo(request.input(), deadlineNanos);
            validateVideoInput(inputProbe);
            runFfmpeg(List.of(
                    "-threads", "1",
                    "-i", request.input().toString(),
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-vf", boundedScale(request.maxDimension()),
                    "-c:v", "libx264",
                    "-threads:v", "1",
                    "-preset", "veryfast",
                    "-crf", "25",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-threads:a", "1",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    "-map_metadata", "-1",
                    mediaPath.toString()
            ), deadlineNanos);
            requireNonEmptyOutput(mediaPath);
            if (thumbnailPath != null) {
                createThumbnail(mediaPath, thumbnailPath, deadlineNanos);
            }
            ProbeResult probe = probeProcessedMedia(mediaPath, deadlineNanos);
            return new ProcessedMedia(
                    mediaPath,
                    thumbnailPath,
                    "video/mp4",
                    probe.width(),
                    probe.height(),
                    probe.durationSeconds()
            );
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(mediaPath);
            deleteQuietly(thumbnailPath);
            throw exception;
        }
    }

    private void createThumbnail(Path source, Path target, long deadlineNanos) throws IOException {
        try {
            runThumbnail(source, target, true, deadlineNanos);
            requireNonEmptyOutput(target);
        } catch (MediaProcessingTimeoutException exception) {
            throw exception;
        } catch (MediaProcessingException exception) {
            deleteQuietly(target);
            runThumbnail(source, target, false, deadlineNanos);
            requireNonEmptyOutput(target);
        }
    }

    private void runThumbnail(Path source, Path target, boolean seek, long deadlineNanos) throws IOException {
        List<String> arguments = new ArrayList<>();
        if (seek) {
            arguments.addAll(List.of("-ss", "0.1"));
        }
        arguments.addAll(List.of(
                "-threads", "1",
                "-i", source.toString(),
                "-vf", boundedScale(THUMBNAIL_MAX_DIMENSION),
                "-frames:v", "1",
                "-c:v", "libwebp",
                "-threads:v", "1",
                "-quality", "76",
                "-compression_level", "4",
                target.toString()
        ));
        runFfmpeg(arguments, deadlineNanos);
    }

    private void requireNonEmptyOutput(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) == 0) {
                throw new MediaProcessingException("미디어 변환 결과 파일이 비어 있습니다.");
            }
        } catch (IOException exception) {
            throw new MediaProcessingException("미디어 변환 결과 파일을 확인할 수 없습니다.", exception);
        }
    }

    private String boundedScale(int maxDimension) {
        int safeMaximum = Math.max(64, maxDimension);
        return "scale=w='min(" + safeMaximum + ",iw)':h='min(" + safeMaximum
                + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2";
    }

    private void runFfmpeg(List<String> arguments, long deadlineNanos) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.add("-filter_threads");
        command.add("1");
        command.add("-filter_complex_threads");
        command.add("1");
        command.addAll(arguments);
        String output = run(command, deadlineNanos);
        if (!output.isBlank()) {
            log.debug("FFmpeg output: {}", output);
        }
    }

    private ProbeResult probeRequiredVideo(Path mediaPath, long deadlineNanos) throws IOException {
        ProbeResult probe = readProbe(mediaPath, deadlineNanos);
        if (probe.width() == null || probe.width() <= 0 || probe.height() == null || probe.height() <= 0) {
            throw new MediaProcessingException("동영상 해상도를 확인할 수 없습니다. 지원되는 동영상을 선택해주세요.");
        }
        if (probe.durationSeconds() == null
                || !Double.isFinite(probe.durationSeconds())
                || probe.durationSeconds() <= 0) {
            throw new MediaProcessingException("동영상 재생 시간을 확인할 수 없습니다. 지원되는 동영상을 선택해주세요.");
        }
        return probe;
    }

    private ProbeResult probeProcessedMedia(Path mediaPath, long deadlineNanos) {
        try {
            return readProbe(mediaPath, deadlineNanos);
        } catch (MediaProcessingTimeoutException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Could not read processed media metadata. path={}", mediaPath, exception);
            return new ProbeResult(null, null, null);
        }
    }

    private ProbeResult readProbe(Path mediaPath, long deadlineNanos) throws IOException {
        List<String> command = List.of(
                ffprobeCommand,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,duration:format=duration",
                "-of", "json",
                mediaPath.toString()
        );
        String probeOutput = run(command, deadlineNanos);
        try {
            JsonNode root = objectMapper.readTree(probeOutput);
            JsonNode streams = root.path("streams");
            JsonNode stream = streams.isArray() && !streams.isEmpty() ? streams.get(0) : null;
            Integer width = stream != null && stream.path("width").canConvertToInt()
                    ? stream.path("width").asInt() : null;
            Integer height = stream != null && stream.path("height").canConvertToInt()
                    ? stream.path("height").asInt() : null;
            Double duration = parseDouble(root.path("format").path("duration").asText(null));
            if (duration == null && stream != null) {
                duration = parseDouble(stream.path("duration").asText(null));
            }
            return new ProbeResult(width, height, duration);
        } catch (Exception exception) {
            throw new MediaProcessingException(
                    "동영상 정보를 확인할 수 없습니다. 지원되는 동영상을 선택해주세요.",
                    exception
            );
        }
    }

    private void validateVideoInput(ProbeResult probe) {
        int width = probe.width();
        int height = probe.height();
        long pixels = (long) width * height;
        if (width > maxInputVideoDimension || height > maxInputVideoDimension || pixels > maxInputVideoPixels) {
            throw new MediaProcessingException(
                    "동영상 해상도는 긴 변 " + maxInputVideoDimension + "px, 총 "
                            + maxInputVideoPixels + "픽셀 이하여야 합니다."
            );
        }
        if (probe.durationSeconds() > maxInputVideoDurationSeconds) {
            throw new MediaProcessingException(
                    "동영상 길이는 " + formatLimit(maxInputVideoDurationSeconds) + "초 이하여야 합니다."
            );
        }
    }

    private String formatLimit(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private String run(List<String> command, long deadlineNanos) throws IOException {
        Process process;
        try {
            ensureTimeRemaining(deadlineNanos);
            process = processStarter.start(List.copyOf(command));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "미디어 처리 실행 파일을 찾을 수 없습니다. Docker 이미지 또는 app.media 명령 설정을 확인해주세요.",
                    exception
            );
        }

        ProcessOutputCollector outputCollector = new ProcessOutputCollector(process.getInputStream());
        try {
            boolean finished = process.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            if (!finished) {
                throw timeoutException();
            }
            outputCollector.await(remainingNanos(deadlineNanos));
            String output = outputCollector.output();
            if (process.exitValue() != 0) {
                throw new MediaProcessingException(
                        "지원하지 않거나 손상된 미디어입니다. " + abbreviate(output));
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminateProcessTree(process);
            throw new MediaProcessingException("미디어 변환이 중단되었습니다.", exception);
        } catch (MediaProcessingTimeoutException exception) {
            terminateProcessTree(process);
            throw exception;
        } finally {
            if (process.isAlive()) {
                terminateProcessTree(process);
            }
        }
    }

    private void ensureTimeRemaining(long deadlineNanos) {
        remainingNanos(deadlineNanos);
    }

    private long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw timeoutException();
        }
        return remaining;
    }

    private MediaProcessingTimeoutException timeoutException() {
        return new MediaProcessingTimeoutException(
                "미디어 처리 시간이 " + timeout.toSeconds() + "초를 초과했습니다. 더 짧은 동영상을 선택해주세요."
        );
    }

    void terminateProcessTree(Process process) {
        List<ProcessHandle> handles;
        try {
            ProcessHandle root = process.toHandle();
            handles = Stream.concat(root.descendants(), Stream.of(root)).toList();
        } catch (RuntimeException exception) {
            process.destroyForcibly();
            return;
        }

        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The caller still receives the original processing failure.
        }
    }

    private record ProbeResult(Integer width, Integer height, Double durationSeconds) {
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private static final class ProcessOutputCollector {
        private static final int MAX_CAPTURE_BYTES = 8 * 1024;

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final Thread thread;

        private ProcessOutputCollector(InputStream input) {
            thread = new Thread(() -> drain(input), "media-process-output");
            thread.setDaemon(true);
            thread.start();
        }

        private void drain(InputStream input) {
            byte[] buffer = new byte[1024];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAX_CAPTURE_BYTES - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(read, remaining));
                    }
                }
            } catch (IOException ignored) {
                // A timed-out process closes its output stream while the collector is draining it.
            }
        }

        private void await(long timeoutNanos) throws InterruptedException {
            long timeoutMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
            thread.join(timeoutMillis);
            if (thread.isAlive()) {
                throw new MediaProcessingTimeoutException("미디어 처리 결과 수집 시간이 초과했습니다.");
            }
        }

        private String output() {
            return captured.toString(StandardCharsets.UTF_8).trim();
        }
    }
}
