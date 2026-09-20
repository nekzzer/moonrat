package moonrat.server;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class HttpUtil {
    private HttpUtil() {}

    static void send(HttpExchange exchange, int code, String mime, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> parameters = new HashMap<>();
        if (raw == null || raw.isEmpty()) return parameters;

        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) parameters.put(decode(pair), "");
            else parameters.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static int clamp(String value, int min, int max, int fallback) {
        try {
            int number = Integer.parseInt(value);
            return Math.max(min, Math.min(max, number));
        } catch (Exception failure) {
            return fallback;
        }
    }

    static String esc(String value) {
        StringBuilder builder = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (character < 0x20) builder.append(String.format("\\u%04x", (int) character));
                    else builder.append(character);
            }
        }
        return builder.toString();
    }

    static String contentDisposition(String name) {
        String fallback = name.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "").replace("\\", "");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
