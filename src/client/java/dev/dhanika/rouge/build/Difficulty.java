package dev.dhanika.rouge.build;

import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import dev.dhanika.rouge.build.BuildSpec.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which blocks of a solution are shown in the overlay vs. left for the
 * learner to place, based on difficulty.
 * <p>
 * Each level removes a fixed fraction of blocks: easy 0% (shows everything),
 * medium 30%, hard 50%. Removal is <b>biased to hide wiring/logic before inputs and
 * outputs</b> (so the visible scaffold stays meaningful) and is <b>deterministic per
 * circuit</b> (a stable per-coordinate hash breaks ties), so a level looks the same
 * each time. Hidden blocks are still part of the build — the learner must place them.
 */
public final class Difficulty {

    /** Difficulty levels and the fraction of blocks each removes from the overlay. */
    public enum Level {
        EASY(0.0),
        MEDIUM(0.3),
        HARD(0.5);

        public final double removeFraction;

        Level(double removeFraction) {
            this.removeFraction = removeFraction;
        }

        public String lower() {
            return name().toLowerCase();
        }

        /** Parses a level name; null/unknown → EASY. */
        public static Level of(String name) {
            if (name == null) {
                return EASY;
            }
            try {
                return Level.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return EASY;
            }
        }
    }

    private Difficulty() {
    }

    /** Blocks shown in the overlay at this level. */
    public static List<BlockEntry> shown(BuildSpec spec, Level level) {
        List<BlockEntry> ordered = removalOrder(spec);
        int remove = (int) Math.round(level.removeFraction * ordered.size());
        // The first `remove` in removal order are hidden; the rest are shown.
        return new ArrayList<>(ordered.subList(remove, ordered.size()));
    }

    /** Blocks hidden at this level — i.e. what the learner must build. */
    public static List<BlockEntry> hidden(BuildSpec spec, Level level) {
        List<BlockEntry> ordered = removalOrder(spec);
        int remove = (int) Math.round(level.removeFraction * ordered.size());
        return new ArrayList<>(ordered.subList(0, remove));
    }

    /**
     * Blocks ordered most-removable first: wiring/logic before inputs/outputs,
     * ties broken by a stable coordinate hash for deterministic spread.
     */
    private static List<BlockEntry> removalOrder(BuildSpec spec) {
        List<BlockEntry> list = new ArrayList<>(spec.blocks());
        list.sort(Comparator
                .comparingInt((BlockEntry b) -> removalWeight(b.role()))
                .thenComparingInt(Difficulty::coordHash));
        return list;
    }

    /** Lower weight = hidden earlier. Inputs/outputs are kept longest. */
    private static int removalWeight(String role) {
        return switch (Role.of(role)) {
            case WIRE -> 0;
            case DELAY -> 1;
            case COMPONENT -> 2;
            case SUPPORT -> 3;
            case OTHER -> 4;
            case OUTPUT -> 5;
            case INPUT -> 6;
        };
    }

    private static int coordHash(BlockEntry b) {
        return (b.x() * 73856093) ^ (b.y() * 19349663) ^ (b.z() * 83492791);
    }
}
