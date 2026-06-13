package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete 3D build: its bounding size plus every block to place. This is the
 * single contract that producers (AI build directives, bundled samples) emit and
 * consumers (the ghost renderer, the world placer, the local diff) read.
 * <p>
 * Each {@link BlockEntry} carries a {@code role} (input/output/component/wire/
 * delay/support) so {@link Difficulty} can decide what to hide without re-guessing.
 * Coordinates are local to the build (origin 0,0,0); placement chooses a world
 * anchor.
 */
public record BuildSpec(int sizeX, int sizeY, int sizeZ, List<BlockEntry> blocks) {

    /** One block: local coords, a block-state string, and a learning role. */
    public record BlockEntry(int x, int y, int z, String block, String role) {
    }

    /** Roles, lowercase, matching the JSON contract. Unknown roles fall back to OTHER. */
    public enum Role {
        INPUT, OUTPUT, COMPONENT, WIRE, DELAY, SUPPORT, OTHER;

        public static Role of(String raw) {
            if (raw == null) {
                return OTHER;
            }
            try {
                return Role.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }

    /**
     * Parses a BuildSpec from JSON, tolerating extra prose around it: the first
     * balanced {@code { ... }} object in the text is used (AI replies often wrap
     * JSON in explanation or code fences).
     */
    public static BuildSpec fromJson(String text) {
        String json = extractFirstObject(text);
        if (json == null) {
            throw new IllegalArgumentException("No JSON object found in build text.");
        }

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray blockArr = root.has("blocks") ? root.getAsJsonArray("blocks") : new JsonArray();

        List<BlockEntry> entries = new ArrayList<>(blockArr.size());
        int maxX = 0, maxY = 0, maxZ = 0;
        for (int i = 0; i < blockArr.size(); i++) {
            JsonObject b = blockArr.get(i).getAsJsonObject();
            String block = b.has("block") ? b.get("block").getAsString().trim() : "";
            if (block.isEmpty()) {
                continue;
            }
            int x = intOr(b, "x", 0);
            int y = intOr(b, "y", 0);
            int z = intOr(b, "z", 0);
            String role = b.has("role") && !b.get("role").isJsonNull() ? b.get("role").getAsString() : "other";
            entries.add(new BlockEntry(x, y, z, normalizeBlockId(block), role));
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        int sx = intOr(root, "sizeX", maxX + 1);
        int sy = intOr(root, "sizeY", maxY + 1);
        int sz = intOr(root, "sizeZ", maxZ + 1);
        // Guard against a declared size that's too small for the blocks present.
        sx = Math.max(sx, maxX + 1);
        sy = Math.max(sy, maxY + 1);
        sz = Math.max(sz, maxZ + 1);
        return new BuildSpec(sx, sy, sz, entries);
    }

    /** Reads an int, rounding if a model emits a fractional number (e.g. 0.71 → 1). */
    private static int intOr(JsonObject o, String key, int fallback) {
        return (o.has(key) && !o.get(key).isJsonNull())
                ? (int) Math.round(o.get(key).getAsDouble()) : fallback;
    }

    /** The block id without its state properties, e.g. {@code minecraft:repeater}. */
    public static String baseId(String block) {
        int b = block.indexOf('[');
        return b < 0 ? block : block.substring(0, b);
    }

    /** Ensures the block id has a namespace (defaults to {@code minecraft:}). */
    private static String normalizeBlockId(String block) {
        if (block.contains(":")) {
            return block;
        }
        return "minecraft:" + block;
    }

    /** Returns the substring of the first balanced top-level {@code {...}} object. */
    private static String extractFirstObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
