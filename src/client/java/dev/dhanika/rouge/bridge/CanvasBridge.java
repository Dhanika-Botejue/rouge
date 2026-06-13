package dev.dhanika.rouge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.compile.SketchCompiler;
import dev.dhanika.rouge.teach.LessonManager;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny localhost HTTP bridge (loopback only) that lets the freeform canvas web app
 * hand a sketch to the running mod. Uses the JDK's built-in {@link HttpServer} — no
 * extra dependency. On {@code POST /build} it kicks off an async sketch compile and
 * acks immediately; the result is applied as the active lesson on the main thread.
 */
public final class CanvasBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");
    /** Loopback port the canvas posts to. */
    public static final int PORT = 25599;

    private static HttpServer server;

    private CanvasBridge() {
    }

    public static void start(SketchCompiler compiler) {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/build", exchange -> handleBuild(exchange, compiler));
            server.createContext("/ping", CanvasBridge::handlePing);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rouge-bridge");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            LOGGER.info("[Rouge] Canvas bridge listening on http://127.0.0.1:{}", PORT);
        } catch (IOException e) {
            LOGGER.warn("[Rouge] Could not start canvas bridge on port {}: {}", PORT, e.getMessage());
        }
    }

    private static void handlePing(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if (handlePreflight(exchange)) {
            return;
        }
        respond(exchange, 200, "{\"status\":\"ok\"}");
    }

    private static void handleBuild(HttpExchange exchange, SketchCompiler compiler) throws IOException {
        addCors(exchange);
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String note = optString(root, "note");
            String difficulty = root.has("difficulty") ? optString(root, "difficulty") : "basic";
            String pngBase64 = stripDataUrl(optString(root, "image"));

            // Forward everything except the bulky image as the sketch JSON.
            root.remove("image");
            String sketchJson = root.toString();
            Level level = Level.of(difficulty);

            Minecraft.getInstance().execute(() -> ChatDisplay.system("Got your sketch — compiling a build…"));

            compiler.compile(sketchJson, note, pngBase64).whenComplete((spec, err) ->
                    Minecraft.getInstance().execute(() -> {
                        if (err != null) {
                            ChatDisplay.error("Couldn't compile the sketch: " + rootMessage(err));
                        } else if (spec == null || spec.blocks().isEmpty()) {
                            ChatDisplay.error("The sketch compiled to an empty build — add a note or more components.");
                        } else {
                            LessonManager.setLesson(spec, level);
                            ChatDisplay.system("Built from your sketch: " + spec.blocks().size()
                                    + " blocks (level " + level.lower() + "). Open Litematica (M → Load → "
                                    + LessonManager.OVERLAY_NAME + "); /rouge solution to build it.");
                        }
                    }));

            respond(exchange, 200, "{\"status\":\"accepted\"}");
        } catch (Exception e) {
            respond(exchange, 400, "{\"error\":\"bad request\"}");
        }
    }

    private static String optString(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : "";
    }

    private static String stripDataUrl(String image) {
        if (image == null || image.isEmpty()) {
            return "";
        }
        int comma = image.indexOf(',');
        return (image.startsWith("data:") && comma >= 0) ? image.substring(comma + 1) : image;
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static boolean handlePreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    private static void respond(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String rootMessage(Throwable err) {
        Throwable c = err;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
