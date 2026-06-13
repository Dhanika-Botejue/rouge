package dev.dhanika.rouge.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and indexes the hand-authored primitive circuit library from
 * {@code /rouge/circuits/<id>.json} on the classpath. Lazy-loaded on first access.
 */
public final class CircuitLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    /** Max library entries shown in full detail per request; the rest are listed as ids only. */
    private static final int MAX_DETAILED = 12;

    private static final String[] IDS = {
            // Buildable primitives — hand-authored, verified block data.
            "4-bit-counter", "and-gate", "bubble-column-elevator", "bud-switch",
            "clock-divider", "comparator-latch", "d-latch", "daylight-sensor",
            "dropper-clock", "falling-edge-detector", "item-filter", "lectern-signal",
            "minecart-detector", "nand-gate", "nether-portal", "nor-gate",
            "not-gate", "observer-pulse", "or-gate", "piston-push",
            "piston-tape", "pulse-extender", "redstone-bridge", "redstone-lamp",
            "repeater-delay", "rising-edge-detector", "rs-latch", "sticky-piston-retract",
            "t-flip-flop", "trapdoor-bridge", "vertical-signal-transmitter", "water-stream",
            "xnor-gate", "xor-gate",
            // Blueprint builds — description-only ground truth for larger machines.
            "auto-smelter", "auto-sugarcane-farm", "bamboo-farm", "cobblestone-generator",
            "comparator-detect", "double-piston-extender", "drawbridge", "enderman-farm",
            "flying-machine", "gold-farm", "hidden-staircase", "hopper-clock",
            "iron-farm", "item-elevator", "item-sorter", "kelp-farm",
            "mail-system", "melon-pumpkin-farm", "mob-farm", "monostable",
            "piston-door-2x2", "piston-door-3x3", "piston-door-4x4", "sand-duper",
            "slime-block-elevator", "storage-system", "tnt-cannon", "tnt-duper",
            "torch-clock", "tree-farm", "vault-door", "villager-breeder", "wheat-farm"
    };

    private static Map<String, CircuitPrimitive> cache;

    private CircuitLibrary() {}

    public static List<CircuitPrimitive> getAll() {
        return new ArrayList<>(index().values());
    }

    public static CircuitPrimitive get(String id) {
        return index().get(id);
    }

    /** Compact multi-line summary injected into every AI request. */
    /** Compact multi-line summary injected into every AI request, filtered/ranked dynamically by the query. */
    public static String summary(String query) {
        StringBuilder sb = new StringBuilder(
                "BUILD LIBRARY — match the player's request to these entries.\n"
                + "  [buildable] = has verified block data: retrieve with {\"use\":\"id\"} or stitch via \"parts\".\n"
                + "  [blueprint] = description-only: compose from buildable parts, or generate a custom build that follows the description.\n\n");

        List<CircuitPrimitive> all = getAll();
        if (query == null || query.isBlank()) {
            sb.append("Common circuits:\n");
            for (int i = 0; i < Math.min(15, all.size()); i++) {
                sb.append("  ").append(all.get(i).summaryLine()).append('\n');
            }
            sb.append("\nOther available IDs: ");
            List<String> others = new ArrayList<>();
            for (int i = 15; i < all.size(); i++) {
                others.add(all.get(i).id());
            }
            sb.append(String.join(", ", others)).append('\n');
            return sb.toString();
        }

        // Split query into lowercase alphanumeric words.
        String[] words = queryWords(query);

        // Score each primitive.
        List<ScoredPrimitive> scored = new ArrayList<>();
        for (CircuitPrimitive p : all) {
            scored.add(new ScoredPrimitive(p, score(p, words)));
        }

        // Sort by score descending.
        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        // Show full details for the top scored entries; the rest are listed as bare ids.
        // Bounded by MAX_DETAILED so a broad query (e.g. "piston" matching many entries)
        // can't balloon the prompt and slow down time-to-first-token.
        List<CircuitPrimitive> detailed = new ArrayList<>();
        List<String> compact = new ArrayList<>();

        for (int i = 0; i < scored.size(); i++) {
            ScoredPrimitive sp = scored.get(i);
            if (detailed.size() < MAX_DETAILED && (i < 8 || sp.score > 0)) {
                detailed.add(sp.prim);
            } else {
                compact.add(sp.prim.id());
            }
        }

        sb.append("Relevant/Common circuits:\n");
        for (CircuitPrimitive p : detailed) {
            sb.append("  ").append(p.summaryLine()).append('\n');
        }

        if (!compact.isEmpty()) {
            sb.append("\nOther available IDs: ").append(String.join(", ", compact)).append('\n');
        }

        return sb.toString();
    }

    /**
     * Buildable primitives (those with verified block data) ranked by relevance to the query,
     * best match first. Used by the build browser so the player sees the same ranked matches the
     * AI does. A blank query returns them in library order. Blueprints are excluded because they
     * have no block data to stitch.
     */
    public static List<CircuitPrimitive> rankedBuildable(String query) {
        String[] words = queryWords(query);
        List<ScoredPrimitive> scored = new ArrayList<>();
        for (CircuitPrimitive p : getAll()) {
            if (!p.isBuildable()) continue;
            scored.add(new ScoredPrimitive(p, score(p, words)));
        }
        // Stable sort keeps library order among equal-scoring (e.g. blank-query) entries.
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<CircuitPrimitive> out = new ArrayList<>(scored.size());
        for (ScoredPrimitive sp : scored) {
            out.add(sp.prim);
        }
        return out;
    }

    /** Splits a query into lowercase alphanumeric words; empty/null yields an empty array. */
    private static String[] queryWords(String query) {
        if (query == null || query.isBlank()) return new String[0];
        return query.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").split("\\s+");
    }

    /** Relevance score of one primitive against the query words (id/aliases weigh most). */
    private static int score(CircuitPrimitive p, String[] words) {
        int score = 0;
        String id = p.id().toLowerCase();
        String title = p.title().toLowerCase();
        String desc = p.description().toLowerCase();

        for (String w : words) {
            if (w.isEmpty()) continue;
            if (id.equals(w)) {
                score += 15;
            } else if (id.contains(w)) {
                score += 8;
            }

            if (title.contains(w)) {
                score += 6;
            }

            for (String alias : p.aliases()) {
                String a = alias.toLowerCase();
                if (a.equals(w)) {
                    score += 10;
                } else if (a.contains(w)) {
                    score += 5;
                }
            }

            if (desc.contains(w)) {
                score += 2;
            }
        }
        return score;
    }

    private static final class ScoredPrimitive {
        final CircuitPrimitive prim;
        final int score;

        ScoredPrimitive(CircuitPrimitive prim, int score) {
            this.prim = prim;
            this.score = score;
        }
    }

    private static synchronized Map<String, CircuitPrimitive> index() {
        if (cache != null) return cache;
        Map<String, CircuitPrimitive> map = new LinkedHashMap<>();
        for (String id : IDS) {
            String path = "/rouge/circuits/" + id + ".json";
            try (InputStream in = CircuitLibrary.class.getResourceAsStream(path)) {
                if (in == null) {
                    LOGGER.warn("[Rouge] Circuit not found on classpath: {}", path);
                    continue;
                }
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                CircuitPrimitive p = CircuitPrimitive.fromJson(json);
                map.put(p.id(), p);
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Failed to load circuit {}: {}", id, e.getMessage());
            }
        }
        cache = Collections.unmodifiableMap(map);
        LOGGER.info("[Rouge] Loaded {} circuit primitives.", cache.size());
        return cache;
    }
}
