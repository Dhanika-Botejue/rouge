package dev.dhanika.rouge.compile;

import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.prompt.SystemPrompts;

import java.util.concurrent.CompletableFuture;

/**
 * Turns a learner's sketch (structured JSON + note + PNG) into a 3D {@link BuildSpec}
 * via the vision model. Reuses the shared {@link OpenRouterClient}; the structured
 * JSON is the primary signal and the PNG is auxiliary context.
 */
public final class SketchCompiler {

    private final OpenRouterClient client;

    public SketchCompiler(OpenRouterClient client) {
        this.client = client;
    }

    /**
     * @param sketchJson structured components + strokes JSON from the canvas
     * @param note       the learner's text description of the goal
     * @param pngBase64  base64 PNG of the sketch (no data-URL prefix), or null
     */
    public CompletableFuture<BuildSpec> compile(String sketchJson, String note, String pngBase64) {
        String system = SystemPrompts.sketchCompiler();
        String user = "GOAL NOTE: " + (note == null ? "" : note)
                + "\n\nSKETCH JSON:\n" + sketchJson
                + "\n\nReturn ONLY the BuildSpec JSON.";
        return client.completeVision(system, user, pngBase64).thenApply(BuildSpec::fromJson);
    }
}
