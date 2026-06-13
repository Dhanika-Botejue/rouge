package dev.dhanika.rouge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches the live OpenRouter model list once and caches the IDs of all free
 * text models. These are used as the fallback chain in {@link OpenRouterClient}
 * so stale hardcoded model IDs never cause "no endpoints found" errors again.
 */
public final class ModelDiscovery {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");
    private static final String MODELS_URL = "https://openrouter.ai/api/v1/models";
    private static final AtomicReference<List<String>> cache = new AtomicReference<>(null);

    private ModelDiscovery() {}

    /**
     * Returns cached free model IDs if available, otherwise fetches them asynchronously
     * and returns an empty list (the caller will use its hardcoded fallbacks this turn).
     */
    public static List<String> getFreeModels(String token, HttpClient http) {
        List<String> cached = cache.get();
        if (cached != null) return cached;

        // Fetch in background; this turn uses hardcoded fallbacks.
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(MODELS_URL))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return;

                List<String> ids = parseFreeModels(resp.body());
                if (!ids.isEmpty()) {
                    cache.set(Collections.unmodifiableList(ids));
                    LOGGER.info("[Rouge] Discovered {} free models.", ids.size());
                }
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Model discovery failed: {}", e.getMessage());
            }
        });

        return List.of(); // empty → caller uses hardcoded list this turn
    }

    /** Clears the cache (e.g. on disconnect so the next session re-fetches). */
    public static void invalidate() {
        cache.set(null);
    }

    private static List<String> parseFreeModels(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data == null) return ids;

            for (JsonElement el : data) {
                JsonObject model = el.getAsJsonObject();
                String id = model.has("id") ? model.get("id").getAsString() : null;
                if (id == null) continue;

                // A model is free when both prompt and completion pricing are "0".
                if (model.has("pricing")) {
                    JsonObject pricing = model.getAsJsonObject("pricing");
                    String prompt = pricing.has("prompt") ? pricing.get("prompt").getAsString() : "?";
                    String completion = pricing.has("completion") ? pricing.get("completion").getAsString() : "?";
                    if ("0".equals(prompt) && "0".equals(completion)) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[Rouge] Could not parse model list: {}", e.getMessage());
        }
        return ids;
    }
}
