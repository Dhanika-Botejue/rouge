package dev.dhanika.rouge.voice;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Async ElevenLabs text-to-speech client. Returns raw PCM audio bytes suitable for
 * {@link PcmAudioPlayer}.
 */
public final class ElevenLabsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private final ElevenLabsConfig config;
    private final HttpClient http;

    public ElevenLabsClient(ElevenLabsConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public boolean hasKey() {
        return config.hasToken();
    }

    public CompletableFuture<byte[]> synthesize(String text) {
        if (!config.hasToken()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No ElevenLabs API key. Set " + ElevenLabsConfig.TOKEN_ENV_VAR + "."));
        }
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(new byte[0]);
        }

        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", ElevenLabsConfig.MODEL_ID);

        JsonObject voiceSettings = new JsonObject();
        voiceSettings.addProperty("stability", 0.45);
        voiceSettings.addProperty("similarity_boost", 0.8);
        body.add("voice_settings", voiceSettings);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(45))
                .header("xi-api-key", config.token())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/pcm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        return resp.body();
                    }
                    String detail = summarizeError(resp.statusCode(), resp.body());
                    throw new RuntimeException(detail);
                })
                .whenComplete((audio, err) -> {
                    if (err != null) {
                        LOGGER.warn("[Rouge] ElevenLabs TTS failed: {}", err.getMessage());
                    }
                });
    }

    private static String summarizeError(int status, byte[] body) {
        String snippet = "";
        if (body != null && body.length > 0) {
            snippet = new String(body, 0, Math.min(body.length, 200));
        }
        return switch (status) {
            case 401 -> "ElevenLabs rejected the API key (401). Check " + ElevenLabsConfig.TOKEN_ENV_VAR + ".";
            case 402, 403 -> "ElevenLabs: insufficient credits or access denied (" + status + ").";
            case 429 -> "ElevenLabs rate limit hit (429). Wait a moment and try again.";
            default -> "ElevenLabs error (HTTP " + status + ")." + (snippet.isBlank() ? "" : " " + snippet);
        };
    }
}
