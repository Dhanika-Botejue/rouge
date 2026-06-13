package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a build directive emitted by the AI inside a {@code ```rougebuild} (or legacy
 * {@code ```stepplan}) fence into a concrete {@link StepPlan}.
 *
 * <p>Three shapes are supported, in priority order:
 * <ol>
 *   <li><b>Retrieve</b> — {@code {"use":"rs-latch"}}: returns the library primitive's
 *       hand-authored, verified steps verbatim.</li>
 *   <li><b>Compose</b> — {@code {"parts":[{"use":"id","dx":0,"dy":0,"dz":0}], "steps":[...]}}:
 *       stitches several library primitives together at offsets, then appends optional
 *       extra wiring steps. Part steps are cumulative within each part; the appended
 *       {@code steps} are <i>additive</i> (new blocks only).</li>
 *   <li><b>Custom</b> — {@code {"steps":[...]}} with explicit, <i>cumulative</i> blocks:
 *       a fully generated build for anything not in the library.</li>
 * </ol>
 *
 * All coordinate math is local to the plan; the in-world anchor is applied later by the
 * renderer. Every step's block list is normalised to be cumulative so the hologram can
 * diff consecutive steps to highlight what's new.
 */
public final class BuildDirective {

    private BuildDirective() {}

    public static StepPlan resolve(String rawJson) {
        JsonObject root = JsonParser.parseString(rawJson.trim()).getAsJsonObject();
        String circuit = root.has("circuit") ? root.get("circuit").getAsString() : null;

        // 1. Retrieve a single library build verbatim.
        if (root.has("use")) {
            CircuitPrimitive p = CircuitLibrary.get(root.get("use").getAsString());
            if (p != null && p.isBuildable()) {
                return circuit == null ? p.toStepPlan() : StepPlan.of(circuit, p.steps());
            }
            // Unknown / blueprint id — fall through to other shapes if present.
        }

        // 2. Compose from parts (+ optional additive wiring steps).
        if (root.has("parts")) {
            return compose(circuit, root.getAsJsonArray("parts"),
                    root.has("steps") ? root.getAsJsonArray("steps") : null);
        }

        // 3. Custom build with explicit cumulative blocks.
        return StepPlan.fromJson(rawJson);
    }

    private static StepPlan compose(String circuit, JsonArray parts, JsonArray extraSteps) {
        List<StepPlan.Step> out = new ArrayList<>();
        // Cumulative blocks contributed by parts already fully placed.
        List<BlockEntry> base = new ArrayList<>();

        for (JsonElement el : parts) {
            JsonObject part = el.getAsJsonObject();
            CircuitPrimitive prim = CircuitLibrary.get(part.get("use").getAsString());
            if (prim == null || !prim.isBuildable()) continue;

            int dx = intOr(part, "dx", 0), dy = intOr(part, "dy", 0), dz = intOr(part, "dz", 0);
            String label = part.has("label") ? part.get("label").getAsString() : prim.title();

            List<StepPlan.Step> pSteps = prim.steps();
            for (StepPlan.Step s : pSteps) {
                List<BlockEntry> cumulative = new ArrayList<>(base);
                cumulative.addAll(offset(s.blocks(), dx, dy, dz));
                out.add(new StepPlan.Step(label + ": " + s.title(), s.explanation(), dedupe(cumulative)));
            }
            if (!pSteps.isEmpty()) {
                base = dedupe(concat(base, offset(pSteps.get(pSteps.size() - 1).blocks(), dx, dy, dz)));
            }
        }

        // Appended wiring/finish steps: additive (each lists only its new blocks).
        if (extraSteps != null) {
            List<BlockEntry> acc = new ArrayList<>(base);
            for (JsonElement el : extraSteps) {
                JsonObject s = el.getAsJsonObject();
                String title = s.has("title") ? s.get("title").getAsString() : "Wiring";
                String expl = s.has("explanation") ? s.get("explanation").getAsString() : "";
                if (s.has("blocks")) {
                    acc = dedupe(concat(acc, readBlocks(s.getAsJsonArray("blocks"))));
                }
                out.add(new StepPlan.Step(title, expl, new ArrayList<>(acc)));
            }
        }

        return StepPlan.of(circuit, out);
    }

    private static List<BlockEntry> offset(List<BlockEntry> blocks, int dx, int dy, int dz) {
        List<BlockEntry> out = new ArrayList<>(blocks.size());
        for (BlockEntry b : blocks) {
            out.add(new BlockEntry(b.x() + dx, b.y() + dy, b.z() + dz, b.block()));
        }
        return out;
    }

    private static List<BlockEntry> readBlocks(JsonArray arr) {
        List<BlockEntry> out = new ArrayList<>(arr.size());
        for (JsonElement be : arr) {
            JsonObject b = be.getAsJsonObject();
            out.add(new BlockEntry(b.get("x").getAsInt(), b.get("y").getAsInt(),
                    b.get("z").getAsInt(), b.get("block").getAsString()));
        }
        return out;
    }

    private static List<BlockEntry> concat(List<BlockEntry> a, List<BlockEntry> b) {
        List<BlockEntry> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /** Collapses duplicate positions, keeping the last writer (so overlays win). */
    private static List<BlockEntry> dedupe(List<BlockEntry> blocks) {
        Map<Long, BlockEntry> byPos = new LinkedHashMap<>();
        for (BlockEntry b : blocks) {
            byPos.put(key(b.x(), b.y(), b.z()), b);
        }
        return new ArrayList<>(byPos.values());
    }

    private static long key(int x, int y, int z) {
        // Offset to keep small negative coords distinct; builds stay well within range.
        return (((long) (x + 512)) << 22) | (((long) (y + 512)) << 11) | (z + 512);
    }

    private static int intOr(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }
}
