package dev.dhanika.rouge.build;

/** Human-readable block id labels for chat and coach context. */
public final class BlockNames {

    private BlockNames() {
    }

    public static String shortName(String id) {
        if (id == null || id.isBlank()) return "air";
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }
}
