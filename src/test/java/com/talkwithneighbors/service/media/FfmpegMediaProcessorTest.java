package com.talkwithneighbors.service.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FfmpegMediaProcessorTest {
    private static final String PORTRAIT_PROBE = """
            {"streams":[{"width":1080,"height":1920,"duration":"60.0"}],"format":{"duration":"60.0"}}
            """;

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsPortraitFullHdAndPinsDecoderEncoderAndFilterThreadsToOne() throws IOException {
        RecordingProcessStarter starter = new RecordingProcessStarter(PORTRAIT_PROBE);
        FfmpegMediaProcessor processor = processor(starter);

        ProcessedMedia result = processor.process(videoRequest());

        assertEquals(1080, result.width());
        assertEquals(1920, result.height());
        assertEquals(3, starter.commands.size());
        List<String> ffmpeg = starter.commands.stream()
                .filter(command -> command.get(0).equals("fake-ffmpeg"))
                .findFirst()
                .orElseThrow();
        assertContainsPair(ffmpeg, "-filter_threads", "1");
        assertContainsPair(ffmpeg, "-filter_complex_threads", "1");
        assertContainsPair(ffmpeg, "-threads", "1");
        assertContainsPair(ffmpeg, "-threads:v", "1");
        assertContainsPair(ffmpeg, "-threads:a", "1");
    }

    @Test
    void rejectsVideoLongerThanConfiguredLimitBeforeStartingFfmpeg() throws IOException {
        RecordingProcessStarter starter = new RecordingProcessStarter(probe(1080, 1920, 60.01));
        FfmpegMediaProcessor processor = processor(starter);

        MediaProcessingException exception = assertThrows(
                MediaProcessingException.class,
                () -> processor.process(videoRequest())
        );

        assertTrue(exception.getMessage().contains("60초 이하"));
        assertEquals(1, starter.commands.size());
        assertEquals("fake-ffprobe", starter.commands.get(0).get(0));
    }

    @Test
    void rejectsFourKAndSquareVideoBeyondPixelBudgetBeforeStartingFfmpeg() throws IOException {
        for (String probe : List.of(probe(3840, 2160, 10), probe(1500, 1500, 10))) {
            RecordingProcessStarter starter = new RecordingProcessStarter(probe);
            FfmpegMediaProcessor processor = processor(starter);

            MediaProcessingException exception = assertThrows(
                    MediaProcessingException.class,
                    () -> processor.process(videoRequest())
            );

            assertTrue(exception.getMessage().contains("2073600픽셀"));
            assertEquals(1, starter.commands.size());
        }
    }

    @Test
    void rejectsMissingVideoMetadataBeforeStartingFfmpeg() throws IOException {
        RecordingProcessStarter starter = new RecordingProcessStarter("{\"streams\":[],\"format\":{}}");
        FfmpegMediaProcessor processor = processor(starter);

        MediaProcessingException exception = assertThrows(
                MediaProcessingException.class,
                () -> processor.process(videoRequest())
        );

        assertTrue(exception.getMessage().contains("해상도를 확인할 수 없습니다"));
        assertEquals(1, starter.commands.size());
    }

    @Test
    void timeoutForciblyTerminatesRootAndDescendantProcessHandles() throws Exception {
        Process process = mock(Process.class);
        ProcessHandle root = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
        when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true);
        when(process.isAlive()).thenReturn(false);
        when(process.toHandle()).thenReturn(root);
        when(process.destroyForcibly()).thenReturn(process);
        when(root.descendants()).thenAnswer(invocation -> Stream.of(child));
        when(root.isAlive()).thenReturn(true);
        when(child.isAlive()).thenReturn(true);
        FfmpegMediaProcessor processor = processor(command -> process);

        assertThrows(MediaProcessingTimeoutException.class, () -> processor.process(videoRequest()));

        verify(child).destroyForcibly();
        verify(root).destroyForcibly();
        verify(process).destroyForcibly();
    }

    private FfmpegMediaProcessor processor(FfmpegMediaProcessor.ProcessStarter starter) {
        return new FfmpegMediaProcessor(
                "fake-ffmpeg",
                "fake-ffprobe",
                90,
                1,
                60,
                2_073_600,
                1920,
                starter
        );
    }

    private MediaProcessingRequest videoRequest() throws IOException {
        Path input = tempDirectory.resolve("input.mp4");
        Files.write(input, new byte[] {0, 1, 2, 3});
        return new MediaProcessingRequest(
                input,
                tempDirectory.resolve("processed"),
                "clip",
                MediaAssetKind.VIDEO,
                ".mp4",
                false,
                false,
                1920
        );
    }

    private String probe(int width, int height, double duration) {
        return "{\"streams\":[{\"width\":" + width + ",\"height\":" + height
                + "}],\"format\":{\"duration\":\"" + duration + "\"}}";
    }

    private void assertContainsPair(List<String> command, String option, String value) {
        for (int index = 0; index < command.size() - 1; index++) {
            if (option.equals(command.get(index)) && value.equals(command.get(index + 1))) {
                return;
            }
        }
        throw new AssertionError("Missing command pair: " + option + " " + value + " in " + command);
    }

    private static final class RecordingProcessStarter implements FfmpegMediaProcessor.ProcessStarter {
        private final String probeOutput;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingProcessStarter(String probeOutput) {
            this.probeOutput = probeOutput;
        }

        @Override
        public Process start(List<String> command) throws IOException {
            commands.add(command);
            if (command.get(0).equals("fake-ffprobe")) {
                return new CompletedProcess(probeOutput);
            }
            Path output = Path.of(command.get(command.size() - 1));
            Files.createDirectories(output.getParent());
            Files.write(output, new byte[] {1, 2, 3});
            return new CompletedProcess("");
        }
    }

    private static final class CompletedProcess extends Process {
        private final InputStream input;

        private CompletedProcess(String output) {
            input = new ByteArrayInputStream(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // Already complete.
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
