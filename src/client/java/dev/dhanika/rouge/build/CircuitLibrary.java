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

    private static final String[] IDS = {
            // Buildable primitives — hand-authored, verified block data.
            "not-gate", "and-gate", "or-gate",
            "rs-latch", "t-flip-flop",
            "torch-clock", "hopper-clock", "monostable",
            "repeater-delay", "comparator-detect",
            "piston-push", "observer-pulse",
            "redstone-lamp",
            // Blueprint builds — description-only ground truth for larger machines.
            "double-piston-extender", "flying-machine",
            "piston-door-2x2", "piston-door-3x3",
            "tnt-duper", "tnt-cannon", "item-elevator",
            "item-sorter", "auto-sugarcane-farm", "hidden-staircase",
            "auto-smelter", "iron-farm", "slime-block-elevator",
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
    public static String summary() {
        StringBuilder sb = new StringBuilder(
                "BUILD LIBRARY — match the player's request to these entries.\n"
                + "  [buildable] = has verified block data: retrieve with {\"use\":\"id\"} or stitch via \"parts\".\n"
                + "  [blueprint] = description-only: compose from buildable parts, or generate a custom build that follows the description.\n");
        for (CircuitPrimitive p : getAll()) {
            sb.append("  ").append(p.summaryLine()).append('\n');
        }
        return sb.toString();
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
