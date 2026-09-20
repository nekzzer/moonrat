package moonrat.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Auth {
    public static final class Entry {
        public final String role;
        public final String key;
        public final String label;

        Entry(String role, String key, String label) {
            this.role = role;
            this.key = key;
            this.label = label;
        }
    }

    private static final Path STORE = Paths.get("moonrat.keys");
    private static final long SESSION_MILLIS = 24 * 3600 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Long> expiry = new ConcurrentHashMap<>();

    private Auth() {}

    public static void load() throws IOException {
        if (!Files.exists(STORE)) {
            Files.writeString(STORE, "admin|parol123@@|root\n");
        }

        entries.clear();
        for (String line : Files.readAllLines(STORE)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) continue;
            String label = parts.length > 2 ? parts[2] : "";
            entries.put(parts[1], new Entry(parts[0], parts[1], label));
        }

        if (entries.values().stream().noneMatch(entry -> entry.role.equals("admin"))) {
            entries.put("parol123@@", new Entry("admin", "parol123@@", "root"));
            save();
        }
    }

    private static void save() throws IOException {
        StringBuilder data = new StringBuilder();
        for (Entry entry : entries.values()) {
            data.append(entry.role).append('|').append(entry.key).append('|').append(entry.label).append('\n');
        }
        Files.writeString(STORE, data.toString());
    }

    public static Entry identify(HttpExchange exchange) {
        String token = cookie(exchange, "moonrat_session");
        if (token != null) {
            Long until = expiry.get(token);
            if (until == null || until < System.currentTimeMillis()) {
                sessions.remove(token);
                expiry.remove(token);
            } else {
                String linkedKey = sessions.get(token);
                if (linkedKey != null) {
                    Entry entry = entries.get(linkedKey);
                    if (entry != null) return entry;
                }
            }
        }

        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Entry entry = entries.get(header.substring("Bearer ".length()).trim());
            if (entry != null) return entry;
        }

        return null;
    }

    public static boolean handle(HttpExchange exchange, String path, Map<String, String> query, String method)
            throws IOException {
        switch (path) {
            case "/api/login": {
                if (!"POST".equalsIgnoreCase(method)) return false;

                String key = query.getOrDefault("key", "");
                Entry entry = entries.get(key);
                if (entry == null) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ignored) {
                    }
                    HttpUtil.send(exchange, 401, "application/json",
                            "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8));
                    return true;
                }

                String token = newToken();
                sessions.put(token, key);
                expiry.put(token, System.currentTimeMillis() + SESSION_MILLIS);

                boolean secure = exchange instanceof HttpsExchange;
                exchange.getResponseHeaders().add("Set-Cookie",
                        "moonrat_session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                                + (SESSION_MILLIS / 1000) + (secure ? "; Secure" : ""));
                HttpUtil.send(exchange, 200, "application/json",
                        ("{\"ok\":true,\"role\":\"" + entry.role + "\"}").getBytes(StandardCharsets.UTF_8));
                return true;
            }
            case "/api/logout": {
                String token = cookie(exchange, "moonrat_session");
                if (token != null) {
                    sessions.remove(token);
                    expiry.remove(token);
                }
                exchange.getResponseHeaders().add("Set-Cookie", "moonrat_session=; Path=/; HttpOnly; Max-Age=0");
                HttpUtil.send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return true;
            }
            case "/api/session": {
                Entry entry = identify(exchange);
                if (entry == null) {
                    HttpUtil.send(exchange, 401, "application/json",
                            "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                    return true;
                }
                HttpUtil.send(exchange, 200, "application/json",
                        ("{\"role\":\"" + entry.role + "\",\"label\":\"" + HttpUtil.esc(entry.label) + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
                return true;
            }
            case "/api/keys": {
                Entry entry = identify(exchange);
                if (entry == null) {
                    HttpUtil.send(exchange, 401, "application/json",
                            "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                    return true;
                }
                if (!"admin".equals(entry.role)) {
                    HttpUtil.send(exchange, 403, "application/json",
                            "{\"error\":\"admin only\"}".getBytes(StandardCharsets.UTF_8));
                    return true;
                }

                if ("GET".equalsIgnoreCase(method)) {
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (Entry other : entries.values()) {
                        if (!first) json.append(',');
                        first = false;
                        json.append("{\"key\":\"").append(HttpUtil.esc(other.key))
                                .append("\",\"role\":\"").append(other.role)
                                .append("\",\"label\":\"").append(HttpUtil.esc(other.label))
                                .append("\",\"current\":").append(other.key.equals(entry.key)).append('}');
                    }
                    HttpUtil.send(exchange, 200, "application/json",
                            json.append(']').toString().getBytes(StandardCharsets.UTF_8));
                    return true;
                }

                if ("POST".equalsIgnoreCase(method)) {
                    String label = query.getOrDefault("label", "");
                    String role = "admin".equals(query.get("role")) ? "admin" : "operator";
                    Entry created = add(role, label);
                    HttpUtil.send(exchange, 200, "application/json",
                            ("{\"ok\":true,\"key\":\"" + HttpUtil.esc(created.key)
                                    + "\",\"role\":\"" + created.role
                                    + "\",\"label\":\"" + HttpUtil.esc(created.label) + "\"}")
                                    .getBytes(StandardCharsets.UTF_8));
                    return true;
                }

                if ("DELETE".equalsIgnoreCase(method)) {
                    String key = query.getOrDefault("key", "");
                    if (key.equals(entry.key)) {
                        HttpUtil.send(exchange, 400, "application/json",
                                "{\"error\":\"cannot delete current key\"}".getBytes(StandardCharsets.UTF_8));
                        return true;
                    }
                    if (!remove(key)) {
                        HttpUtil.send(exchange, 400, "application/json",
                                "{\"error\":\"not found or last admin\"}".getBytes(StandardCharsets.UTF_8));
                        return true;
                    }
                    HttpUtil.send(exchange, 200, "application/json",
                            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                    return true;
                }

                return false;
            }
            default:
                return false;
        }
    }

    private static Entry add(String role, String label) throws IOException {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder key = new StringBuilder("mr-");
        for (byte value : random) key.append(String.format("%02x", value));

        Entry entry = new Entry(role, key.toString(), label == null || label.isBlank() ? "unnamed" : label);
        entries.put(entry.key, entry);
        save();
        return entry;
    }

    private static boolean remove(String key) throws IOException {
        Entry entry = entries.get(key);
        if (entry == null) return false;

        long admins = entries.values().stream().filter(other -> other.role.equals("admin")).count();
        if (entry.role.equals("admin") && admins <= 1) return false;

        entries.remove(key);
        sessions.entrySet().removeIf(tokenEntry -> {
            boolean drop = tokenEntry.getValue().equals(key);
            if (drop) expiry.remove(tokenEntry.getKey());
            return drop;
        });
        save();
        return true;
    }

    private static String newToken() {
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        StringBuilder token = new StringBuilder();
        for (byte value : random) token.append(String.format("%02x", value));
        return token.toString();
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;

        for (String header : cookies) {
            for (String pair : header.split(";")) {
                String[] parts = pair.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(name)) return parts[1];
            }
        }
        return null;
    }
}
