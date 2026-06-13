package dev.dhanika.rouge.prompt;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads Rouge's system prompts from {@code resources/rouge/*.txt}.
 * <p>
 * Each prompt is read once from the classpath and cached. Keeping prompts in
 * resources (rather than Java strings) makes them easy to edit and swap without
 * recompiling. A short hardcoded fallback is used if a resource is missing.
 */
public final class SystemPrompts {

    private static final Map<String, String> cache = new HashMap<>();

    private static final String TUTOR_FALLBACK =
            "You are Rouge, an expert Minecraft redstone tutor in the player's chat. "
            + "Give concise, practical, buildable redstone advice for modern Java Edition. "
            + "Plain text only.";

    private static final String COMPILER_FALLBACK =
            "You convert a redstone sketch into a 3D Minecraft block list. Reply with ONLY "
            + "JSON: {\"sizeX\":int,\"sizeY\":int,\"sizeZ\":int,\"blocks\":[{\"x\":int,\"y\":int,"
            + "\"z\":int,\"block\":\"minecraft:...\",\"role\":\"input|output|component|wire|delay|support\"}]}.";

    private SystemPrompts() {
    }

    /** The redstone-tutor / chat persona. */
    public static String redstoneTutor() {
        return load("/rouge/system_prompt.txt", TUTOR_FALLBACK);
    }

    /** The sketch→build vision-compiler instructions. */
    public static String sketchCompiler() {
        return load("/rouge/sketch_compiler.txt", COMPILER_FALLBACK);
    }

    private static String load(String path, String fallback) {
        return cache.computeIfAbsent(path, p -> {
            try (InputStream in = SystemPrompts.class.getResourceAsStream(p)) {
                if (in == null) {
                    return fallback;
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return text.isEmpty() ? fallback : text;
            } catch (Exception e) {
                return fallback;
            }
        });
    }
}
