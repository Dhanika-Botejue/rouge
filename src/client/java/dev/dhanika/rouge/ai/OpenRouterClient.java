package dev.dhanika.rouge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Async client for the OpenRouter chat completions API.
 * <p>
 * Knows nothing about Minecraft — it takes a list of {@link ChatMessage} and
 * returns a {@link CompletableFuture} of the assistant's reply text. The HTTP
 * call runs off the caller's thread via {@link HttpClient#sendAsync}, so it never
 * blocks the game's render thread. Callers are responsible for hopping back onto
 * the main thread before touching Minecraft state.
 */
public final class OpenRouterClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private final OpenRouterConfig config;
    private final HttpClient http;

    public OpenRouterClient(OpenRouterConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                // Short connect timeout so a dead endpoint fails over to a fallback fast;
                // the per-request body timeout below stays generous for slow generations.
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Warms up the client ahead of the first real request: triggers async free-model
     * discovery so the fallback chain is ready, and opens the TLS connection to OpenRouter
     * so the first completion doesn't pay the handshake cost. Safe to call repeatedly.
     */
    public void prewarm() {
        if (!config.hasToken()) return;
        ModelDiscovery.getFreeModels(config.token(), http);
    }

    /** {@link #complete(List, Consumer)} with no status callback. */
    public CompletableFuture<String> complete(List<ChatMessage> history) {
        return complete(history, status -> {});
    }

    /**
     * Sends the conversation to OpenRouter. On any non-200 response (rate limit,
     * no endpoint, unavailable) automatically tries the next model in the chain —
     * first the user-set primary, then every free model discovered live from
     * OpenRouter's API, then the hardcoded emergency fallbacks.
     * <p>
     * {@code onStatus} is notified each time the client falls back to another model, with a
     * human-readable line (the model that failed, why, and what's being tried next). The
     * callback may run on an HTTP worker thread, so callers must hop to their own thread
     * before touching game state.
     */
    public CompletableFuture<String> complete(List<ChatMessage> history, Consumer<String> onStatus) {
        JsonArray messages = new JsonArray();
        for (ChatMessage m : history) {
            messages.add(textMessage(m.role(), m.content()));
        }
        // Kick off model discovery in background if not done yet.
        ModelDiscovery.getFreeModels(config.token(), http);
        return sendWithFallback(messages, 0, onStatus);
    }

    private CompletableFuture<String> sendWithFallback(JsonArray messages, int attempt, Consumer<String> onStatus) {
        List<String> chain = buildChain();
        if (attempt >= chain.size()) {
            return CompletableFuture.failedFuture(new RuntimeException(
                    "No available models found. Check openrouter.ai/models and use /rouge model <id>."));
        }
        String model = chain.get(attempt);
        String next = (attempt + 1 < chain.size()) ? chain.get(attempt + 1) : null;
        return sendRaw(model, messages, Duration.ofSeconds(30))
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status != 200) {
                        // Any failure — no endpoint, rate limit, unavailable — try next.
                        notifyFallback(onStatus, model + " returned HTTP " + status, next);
                        return sendWithFallback(messages, attempt + 1, onStatus);
                    }
                    try {
                        return CompletableFuture.completedFuture(parseReply(response));
                    } catch (RuntimeException e) {
                        // Empty/bad response from this model — try next.
                        notifyFallback(onStatus, model + " gave a bad reply (" + e.getMessage() + ")", next);
                        return sendWithFallback(messages, attempt + 1, onStatus);
                    }
                });
    }

    /** Logs (at INFO so it's visible in the dev console) and reports a fallback to the caller. */
    private void notifyFallback(Consumer<String> onStatus, String reason, String next) {
        String line = next != null
                ? reason + " — trying " + next + "…"
                : reason + " — no more models to try.";
        LOGGER.info("[Rouge] {}", line);
        try {
            onStatus.accept(line);
        } catch (Exception ignored) {
            // A misbehaving status sink must never break the request flow.
        }
    }

    private List<String> buildChain() {
        List<String> chain = new ArrayList<>();
        // 1. User-set primary.
        chain.add(config.model());
        // 2. Live-discovered free models (populated after first complete() call).
        List<String> discovered = ModelDiscovery.getFreeModels(config.token(), http);
        for (String id : discovered) {
            if (!chain.contains(id)) chain.add(id);
        }
        // 3. Hardcoded emergency fallbacks (in case discovery hasn't finished yet).
        for (String id : OpenRouterConfig.FALLBACKS) {
            if (!chain.contains(id)) chain.add(id);
        }
        return chain;
    }

    private CompletableFuture<HttpResponse<String>> sendRaw(String model, JsonArray messages, Duration timeout) {
        if (!config.hasToken()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No OpenRouter API key. Set the " + OpenRouterConfig.TOKEN_ENV_VAR + " environment variable."));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("max_tokens", 4096);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.endpoint()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    // Optional OpenRouter attribution headers.
                    .header("HTTP-Referer", "https://github.com/dhanika/rouge")
                    .header("X-Title", "Rouge")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        return http.sendAsync(request, BodyHandlers.ofString());
    }

    private JsonObject textMessage(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }

    /** Extracts {@code choices[0].message.content}, or throws a readable error. */
    private String parseReply(HttpResponse<String> response) {
        int status = response.statusCode();
        String raw = response.body();

        if (status != 200) {
            throw new RuntimeException(describeError(status, raw));
        }

        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("OpenRouter returned no choices — the model may be overloaded. Try again.");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    ? choice.get("finish_reason").getAsString() : "unknown";

            JsonObject message = choice.getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                // content_filter or tool-call-only response — give a useful hint
                String hint = switch (finishReason) {
                    case "content_filter" -> "The model's content filter blocked the response. Try rephrasing.";
                    case "tool_calls"     -> "The model responded with tool calls instead of text. Try a different model.";
                    default -> "The model returned an empty response (finish_reason=" + finishReason + "). Try again.";
                };
                throw new RuntimeException(hint);
            }
            String content = message.get("content").getAsString().trim();
            if (content.isEmpty()) {
                throw new RuntimeException("The model returned a blank response (finish_reason=" + finishReason + "). Try again.");
            }
            if ("length".equals(finishReason)) {
                // Response was cut off at the token limit — the JSON fence will be incomplete.
                throw new RuntimeException("The model's response was cut off (token limit reached). Try asking for a simpler build, or switch models with /rouge model.");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse OpenRouter response: " + e.getMessage());
        }
    }

    /** Turns an HTTP error into a short, user-facing message. */
    private String describeError(int status, String raw) {
        String detail = extractErrorMessage(raw);
        String suffix = detail.isEmpty() ? "" : " — " + detail;
        return switch (status) {
            case 401 -> "OpenRouter rejected the API key (401). Check " + OpenRouterConfig.TOKEN_ENV_VAR + "." + suffix;
            case 402 -> "OpenRouter: insufficient credits (402)." + suffix;
            case 429 -> "OpenRouter rate limit hit (429). Wait a moment and try again." + suffix;
            default -> "OpenRouter error (HTTP " + status + ")." + suffix;
        };
    }

    /** Best-effort pull of {@code error.message} from an error body. */
    private String extractErrorMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message") && !error.get("message").isJsonNull()) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Not JSON, or unexpected shape — fall through.
        }
        return "";
    }
}
