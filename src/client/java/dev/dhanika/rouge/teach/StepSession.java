package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives an active step-by-step build: tracks the current plan, step index, and a fixed
 * world anchor, and renders each step as an in-world hologram via {@link GhostRenderer}.
 *
 * <p>Steps carry cumulative block lists, so each step shows the whole build-so-far while
 * the blocks added <i>this</i> step are highlighted. The player advances by telling Rouge
 * "next" (handled in the session) rather than typing a command.
 */
public final class StepSession {

    /** Result of advancing, so the session knows whether the build is still running. */
    public enum Advance { MORE, DONE, INACTIVE }

    private static StepPlan plan;
    private static int stepIndex;
    private static BlockPos anchor;

    private StepSession() {}

    public static boolean isActive() {
        return plan != null;
    }

    /** Begins a build: anchors it in front of the player and shows step 1. */
    public static void start(StepPlan p) {
        plan = p;
        stepIndex = 0;
        anchor = computeAnchor();

        int total = p.steps().size();
        ChatDisplay.system("Building " + p.circuit() + " — " + total + " step"
                + (total == 1 ? "" : "s") + ". I'll project each step as a hologram in front of you.");
        if (total == 0) {
            ChatDisplay.system("…but this build has no steps. Ask me to try again.");
            plan = null;
            return;
        }
        showStep();
    }

    /** Advances to the next step (or finishes). Returns whether the build continues. */
    public static Advance next() {
        if (plan == null) {
            ChatDisplay.system("No active build. Ask me to teach you something to start one.");
            return Advance.INACTIVE;
        }
        stepIndex++;
        if (stepIndex >= plan.steps().size()) {
            String circuit = plan.circuit();
            GhostRenderer.clear();
            plan = null;
            ChatDisplay.system("That's the whole " + circuit + " — nice work! The hologram's cleared. Ask me for another build any time.");
            return Advance.DONE;
        }
        showStep();
        return Advance.MORE;
    }

    /** Re-shows the current step (used by /rouge step). */
    public static void showCurrent() {
        if (plan == null) {
            ChatDisplay.system("No active build.");
            return;
        }
        showStep();
    }

    /** Cancels the build and clears the hologram. */
    public static void stop() {
        if (plan == null) return;
        plan = null;
        GhostRenderer.clear();
        ChatDisplay.system("Stopped the build and cleared the hologram. Ping me when you want to pick it back up.");
    }

    public static void reset() {
        plan = null;
        stepIndex = 0;
        anchor = null;
        GhostRenderer.clear();
    }

    /** A one-line reminder of where the player is, for context on mid-build questions. */
    public static String contextLine() {
        if (plan == null) return "";
        StepPlan.Step step = plan.steps().get(stepIndex);
        return "ACTIVE BUILD: " + plan.circuit() + ", step " + (stepIndex + 1) + "/"
                + plan.steps().size() + " (" + step.title() + "). The player is placing these blocks now.";
    }

    private static void showStep() {
        StepPlan.Step step = plan.steps().get(stepIndex);
        int total = plan.steps().size();

        List<BlockEntry> all = step.blocks();
        List<BlockEntry> added = blocksAddedThisStep();

        GhostRenderer.show(anchor, all, added);

        ChatDisplay.system("Step " + (stepIndex + 1) + "/" + total + ": " + step.title());
        if (!step.explanation().isBlank()) {
            ChatDisplay.print(step.explanation());
        }
        if (stepIndex + 1 < total) {
            ChatDisplay.system("Place the glowing blocks, then say \"next\" when you're ready (or ask me anything).");
        } else {
            ChatDisplay.system("Last step — place the glowing blocks, then say \"next\" to finish.");
        }
    }

    /** Blocks in the current step that weren't in the previous step (by position). */
    private static List<BlockEntry> blocksAddedThisStep() {
        List<BlockEntry> current = plan.steps().get(stepIndex).blocks();
        if (stepIndex == 0) return current;

        Set<Long> prev = new HashSet<>();
        for (BlockEntry b : plan.steps().get(stepIndex - 1).blocks()) {
            prev.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }
        List<BlockEntry> added = new ArrayList<>();
        for (BlockEntry b : current) {
            if (!prev.contains(BlockPos.asLong(b.x(), b.y(), b.z()))) {
                added.add(b);
            }
        }
        // If nothing is positionally new (e.g. a block-state change), highlight the whole
        // step so the player still gets a visible cue.
        return added.isEmpty() ? current : added;
    }

    /** Anchors the build a couple of blocks in front of the player, at foot level. */
    private static BlockPos computeAnchor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return BlockPos.ZERO;
        Direction facing = mc.player.getDirection();
        return mc.player.blockPosition().relative(facing, 2);
    }
}
