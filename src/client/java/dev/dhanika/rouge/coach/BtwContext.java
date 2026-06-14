package dev.dhanika.rouge.coach;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.teach.StepSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a compact system-context message for BTW ("between-the-blocks") coaching, so Rouge can
 * answer a mid-build question grounded in what the player is actually looking at — including the
 * details of every redstone component in the build. The hologram keeps running while the answer
 * is generated; this context is read-only and never touches it.
 */
public final class BtwContext {

    /** Max positions listed per component type before collapsing to a count. */
    private static final int MAX_POSITIONS = 12;

    private BtwContext() {
    }

    /** Picks the richest context available for the current session state. */
    public static String forCurrentState() {
        return StepSession.isActive() ? build() : general();
    }

    /** Context for an active step-by-step build. */
    private static String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("BTW CONTEXT — mid-build coaching (hologram + chat in parallel).\n");
        sb.append("The player sees a translucent hologram in-world RIGHT NOW. Your reply is chat-only; "
                + "the hologram never pauses or clears while you answer.\n\n");

        String line = StepSession.contextLine();
        if (!line.isBlank()) {
            sb.append(line).append('\n');
        }

        appendComponents(sb, StepSession.activeAllBlocks());
        appendCurrentStep(sb, StepSession.activeStepBlocks());
        appendPlayer(sb);

        sb.append("\nWhen they ask about a component, explain THAT block using its position and state above "
                + "(facing, delay, powered, extended) and how its signal connects to its neighbours. "
                + "Do NOT emit build/planning fences or propose a new build — just answer.\n");
        return sb.toString().trim();
    }

    /**
     * Lists every redstone component in the build with its position and full block state, grouped
     * by type, so the model can speak to each one specifically (a repeater's delay, a piston's
     * facing, a torch's role, etc.). Structural filler is summarised as a single count.
     */
    private static void appendComponents(StringBuilder sb, List<BlockEntry> all) {
        if (all.isEmpty()) {
            return;
        }
        // Group by exact block string so identical components (same facing/delay) collapse together,
        // preserving first-seen order for stable, readable output.
        Map<String, List<BlockEntry>> byBlock = new LinkedHashMap<>();
        int structural = 0;
        for (BlockEntry b : all) {
            if (isRedstoneComponent(b.block())) {
                byBlock.computeIfAbsent(b.block(), k -> new ArrayList<>()).add(b);
            } else {
                structural++;
            }
        }

        sb.append("\nFULL BUILD — every redstone component (build-local x,y,z, with block state so you can "
                + "read facing/delay/powered/extended):\n");
        for (Map.Entry<String, List<BlockEntry>> e : byBlock.entrySet()) {
            List<BlockEntry> list = e.getValue();
            sb.append("  ").append(e.getKey()).append(" ×").append(list.size()).append(" @ ");
            int shown = Math.min(list.size(), MAX_POSITIONS);
            for (int i = 0; i < shown; i++) {
                BlockEntry b = list.get(i);
                if (i > 0) sb.append(", ");
                sb.append('(').append(b.x()).append(',').append(b.y()).append(',').append(b.z()).append(')');
            }
            if (list.size() > shown) {
                sb.append(", … (+").append(list.size() - shown).append(" more)");
            }
            sb.append('\n');
        }
        if (structural > 0) {
            sb.append("  structural/solid blocks ×").append(structural).append(" (frame & support)\n");
        }
    }

    private static void appendCurrentStep(StringBuilder sb, List<BlockEntry> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        sb.append("\nPLACING NOW (the glowing blocks for this step):\n");
        int shown = Math.min(blocks.size(), MAX_POSITIONS);
        for (int i = 0; i < shown; i++) {
            BlockEntry b = blocks.get(i);
            sb.append("  ").append(b.x()).append(',').append(b.y()).append(',').append(b.z())
                    .append(" = ").append(b.block()).append('\n');
        }
        if (blocks.size() > shown) {
            sb.append("  … (+").append(blocks.size() - shown).append(" more)\n");
        }
    }

    /** Open coaching when no step build is active — answer the topic fully anyway. */
    private static String general() {
        StringBuilder sb = new StringBuilder();
        sb.append("BTW CONTEXT — open coaching (no active step build).\n");
        sb.append("The player asked a redstone question with /btw. Nothing is being built yet; answer the "
                + "topic fully and never tell them to start a build first.\n");
        appendPlayer(sb);
        sb.append("\nDo NOT emit build/planning fences unless they explicitly ask to build something.\n");
        return sb.toString().trim();
    }

    private static void appendPlayer(StringBuilder sb) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        BlockPos feet = player.blockPosition();
        sb.append("\nPLAYER: world ").append(feet.getX()).append(',').append(feet.getY())
                .append(',').append(feet.getZ())
                .append(", facing ").append(player.getDirection().getName()).append('\n');
    }

    /** True for blocks that carry or react to redstone signal — the parts worth explaining. */
    private static boolean isRedstoneComponent(String block) {
        String s = block.toLowerCase();
        return s.contains("redstone_wire") || s.contains("redstone_torch") || s.contains("redstone_block")
                || s.contains("repeater") || s.contains("comparator") || s.contains("piston")
                || s.contains("observer") || s.contains("lever") || s.contains("button")
                || s.contains("pressure_plate") || s.contains("tripwire") || s.contains("target")
                || s.contains("lamp") || s.contains("dispenser") || s.contains("dropper")
                || s.contains("hopper") || s.contains("note_block") || s.contains("bell")
                || s.contains("daylight_detector") || s.contains("slime_block") || s.contains("honey_block")
                || s.contains("sculk_sensor") || s.contains("lightning_rod") || s.contains("rail");
    }
}
