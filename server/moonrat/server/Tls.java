package moonrat.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class Tls {
    private static final String ALIAS = "moonrat";
    private static final char[] SECRET = "moonrat".toCharArray();
    private static final Path KEYSTORE = Paths.get("certs", "moonrat.p12");

    private Tls() {}

    public static HttpsServer start(int port, HttpHandler handler) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context()));
        server.createContext("/", handler);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static SSLContext context() throws Exception {
        if (!Files.exists(KEYSTORE)) generate();

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(KEYSTORE)) {
            store.load(in, SECRET);
        }

        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, SECRET);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private static void generate() throws Exception {
        Files.createDirectories(KEYSTORE.getParent());

        String javaHome = System.getProperty("java.home");
        Path keytool = Paths.get(javaHome, "bin", isWindows() ? "keytool.exe" : "keytool");

        ProcessBuilder builder = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "825",
                "-storetype", "PKCS12",
                "-keystore", KEYSTORE.toString(),
                "-storepass", new String(SECRET),
                "-keypass", new String(SECRET),
                "-dname", "CN=MoonRAT",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timeout");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("keytool exit " + process.exitValue());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
