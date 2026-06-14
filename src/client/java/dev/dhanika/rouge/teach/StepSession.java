package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.render.GhostRenderer;
import dev.dhanika.rouge.session.RougeSession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives an active step-by-step build: tracks the current plan, step index, and a fixed world
 * anchor, and renders each step as a translucent in-world hologram via {@link GhostRenderer}.
 *
 * <p>Steps carry cumulative block lists, so each step shows the whole build-so-far while the
 * blocks added <i>this</i> step are highlighted. Difficulty (easy/medium/hard) hides a fraction
 * of each step's blocks from the hologram — the learner must place those themselves. The full
 * step (including hidden blocks) is registered with {@link LessonManager} as the diff target, so
 * {@link ProactiveTutor} can detect when the step is complete and auto-advance.
 *
 * <p>The player advances by placing the blocks (auto-advance), by telling Rouge "next", or with
 * {@code /rouge next}.
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

    /** 1-based index of the step currently shown, or 0 if no build is active. */
    public static int activeStepNumber() {
        return plan == null ? 0 : stepIndex + 1;
    }

    /** Total number of steps in the active build, or 0 if none. */
    public static int activeStepTotal() {
        return plan == null ? 0 : plan.steps().size();
    }

    /** Blocks newly placed in the current step (not cumulative from prior steps). */
    public static List<BlockEntry> activeStepBlocks() {
        return plan == null ? List.of() : blocksAddedThisStep();
    }

    /** Every block in the finished build (the final cumulative step), or empty if no build. */
    public static List<BlockEntry> activeAllBlocks() {
        if (plan == null || plan.steps().isEmpty()) return List.of();
        return plan.steps().get(plan.steps().size() - 1).blocks();
    }

    /** Name of the circuit currently being built, or empty if none. */
    public static String activeCircuit() {
        return plan == null ? "" : plan.circuit();
    }

    /** Begins a build: anchors it in front of the player and shows step 1. */
    public static void start(StepPlan p) {
        int total = p.steps().size();
        if (total == 0) {
            ChatDisplay.system("That build came back empty — ask me to try again, or describe it differently.");
            plan = null;
            return;
        }

        plan = p;
        stepIndex = 0;
        anchor = computeAnchor();

        ChatDisplay.system("Building " + p.circuit() + " — " + total + " step"
                + (total == 1 ? "" : "s") + ". The translucent hologram shows what to place; "
                + "I'll say so and move on when each step matches. " + locationLine());
        showStep();
    }

    /**
     * Re-places the active build so its footprint is centered in front of the player again,
     * then re-shows the current step. Lets the player move the hologram without restarting.
     */
    public static void recenter() {
        if (plan == null) {
            ChatDisplay.system("No active build to move. Ask me to teach you something first.");
            return;
        }
        anchor = computeAnchor();
        ChatDisplay.system("Moved the hologram. " + locationLine());
        showStep();
    }

    /** Re-renders the current step (used when difficulty changes). */
    public static void refresh() {
        if (plan == null) return;
        showStep();
    }

    /** Human-readable note of where the hologram is anchored in the world. */
    private static String locationLine() {
        if (anchor == null) return "";
        int[] b = footprint();
        int minX = anchor.getX() + b[0], maxX = anchor.getX() + b[1];
        int minY = anchor.getY() + b[2], maxY = anchor.getY() + b[3];
        int minZ = anchor.getZ() + b[4], maxZ = anchor.getZ() + b[5];
        return "It's floating in front of you, spanning x " + minX + "–" + maxX
                + ", y " + minY + "–" + maxY + ", z " + minZ + "–" + maxZ
                + ". Say \"move\" (or /rouge move) to re-place it where you're standing.";
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
            plan = null;
            LessonManager.clearLesson();
            RougeSession.endBuildMode();
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
        LessonManager.clearLesson();
        RougeSession.endBuildMode();
        ChatDisplay.system("Stopped the build and cleared the hologram. Ping me when you want to pick it back up.");
    }

    public static void reset() {
        plan = null;
        stepIndex = 0;
        anchor = null;
        LessonManager.clearLesson();
    }

    /**
     * Called by {@link ProactiveTutor} when every block of the current step has been placed
     * correctly. Congratulates the player and advances to the next step.
     */
    public static void onStepComplete() {
        if (plan == null) return;
        ChatDisplay.praise("Great job! ✔");
        next();
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
        BuildSpec stepSpec = toBuildSpec(all);
        Level level = LessonManager.level();

        // The full step (including difficulty-hidden blocks) is the diff target for auto-advance.
        LessonManager.setActive(stepSpec, anchor, level);

        // The hologram only shows the difficulty-filtered subset; the new blocks among them glow.
        List<BuildSpec.BlockEntry> shownSpec = Difficulty.shown(stepSpec, level);
        List<BlockEntry> shown = GhostRenderer.fromSpec(shownSpec);

        Set<Long> addedKeys = new HashSet<>();
        for (BlockEntry b : blocksAddedThisStep()) {
            addedKeys.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }
        List<BlockEntry> added = new ArrayList<>();
        for (BlockEntry b : shown) {
            if (addedKeys.contains(BlockPos.asLong(b.x(), b.y(), b.z()))) {
                added.add(b);
            }
        }

        GhostRenderer.show(anchor, shown, added.isEmpty() ? shown : added);

        ChatDisplay.system("Step " + (stepIndex + 1) + "/" + total + ": " + step.title());
        if (!step.explanation().isBlank()) {
            ChatDisplay.step(step.explanation());
        }
        int hidden = stepSpec.blocks().size() - shownSpec.size();
        if (hidden > 0) {
            ChatDisplay.system("(" + level.lower() + ": " + hidden + " block" + (hidden == 1 ? "" : "s")
                    + " hidden — figure those out yourself.)");
        }
        ChatDisplay.system("Place the glowing blocks; I'll move on when the step matches. "
                + "Say \"next\" to skip ahead, or ask me anything.");
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
        // If nothing is positionally new (e.g. a block-state change), treat the whole step as new.
        return added.isEmpty() ? current : added;
    }

    /**
     * Converts a step's cumulative block list into a {@link BuildSpec} for difficulty filtering
     * and diffing. The step plan's entries carry no learning role, so we infer one from the block
     * id (so difficulty still hides wiring/logic before inputs and outputs). Size is the block
     * extent; coordinates are assumed non-negative (as the build library and AI directives emit).
     */
    private static BuildSpec toBuildSpec(List<BlockEntry> blocks) {
        int maxX = 0, maxY = 0, maxZ = 0;
        for (BlockEntry b : blocks) {
            maxX = Math.max(maxX, b.x());
            maxY = Math.max(maxY, b.y());
            maxZ = Math.max(maxZ, b.z());
        }
        List<BuildSpec.BlockEntry> out = new ArrayList<>(blocks.size());
        for (BlockEntry b : blocks) {
            out.add(new BuildSpec.BlockEntry(b.x(), b.y(), b.z(), b.block(), inferRole(b.block())));
        }
        return new BuildSpec(maxX + 1, maxY + 1, maxZ + 1, out);
    }

    /** Best-effort role from a block id, so {@link Difficulty} hides wiring/logic first. */
    private static String inferRole(String blockId) {
        String s = blockId.toLowerCase();
        if (s.contains("redstone_wire")) return "wire";
        if (s.contains("repeater") || s.contains("comparator")) return "delay";
        if (s.contains("lever") || s.contains("button") || s.contains("pressure_plate")
                || s.contains("daylight_detector") || s.contains("tripwire")) return "input";
        if (s.contains("lamp") || s.contains("piston") || s.contains("dispenser") || s.contains("dropper")
                || s.contains("door") || s.contains("note_block") || s.contains("hopper") || s.contains("bell")) return "output";
        if (s.contains("redstone_torch") || s.contains("redstone_block") || s.contains("observer")
                || s.contains("target") || s.contains("slime") || s.contains("honey")) return "component";
        return "support";
    }

    /**
     * Anchors the build so its footprint is centered a few blocks in front of the player,
     * resting at foot level. The build's blocks use world-absolute orientation (their
     * blockstate facings are baked in), so we can't rotate it to face the player — instead
     * we translate it to a predictable, visible spot and report where that is.
     */
    private static BlockPos computeAnchor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || plan == null) return BlockPos.ZERO;

        int[] b = footprint();                 // [minX,maxX, minY,maxY, minZ,maxZ] in build-local coords
        int minX = b[0], maxX = b[1], minY = b[2], minZ = b[4], maxZ = b[5];
        double localCenterX = (minX + maxX) / 2.0;
        double localCenterZ = (minZ + maxZ) / 2.0;

        Direction facing = mc.player.getDirection();
        BlockPos feet = mc.player.blockPosition();

        // Push the build's center forward so the near edge clears the player.
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int ahead = 2 + Math.max(width, depth) / 2;
        int centerX = feet.getX() + facing.getStepX() * ahead;
        int centerZ = feet.getZ() + facing.getStepZ() * ahead;

        // anchor so that anchor + localCenter == world center, and the build's bottom sits at feet level.
        int anchorX = (int) Math.round(centerX - localCenterX);
        int anchorZ = (int) Math.round(centerZ - localCenterZ);
        int anchorY = feet.getY() - minY;
        return new BlockPos(anchorX, anchorY, anchorZ);
    }

    /**
     * Bounding box of the whole build in build-local coordinates, as
     * {@code [minX,maxX, minY,maxY, minZ,maxZ]}. Scans every step so it's correct even if
     * the last step isn't strictly cumulative. Defaults to a unit box for an empty build.
     */
    private static int[] footprint() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        if (plan != null) {
            for (StepPlan.Step step : plan.steps()) {
                for (BlockEntry e : step.blocks()) {
                    minX = Math.min(minX, e.x()); maxX = Math.max(maxX, e.x());
                    minY = Math.min(minY, e.y()); maxY = Math.max(maxY, e.y());
                    minZ = Math.min(minZ, e.z()); maxZ = Math.max(maxZ, e.z());
                }
            }
        }
        if (minX > maxX) return new int[]{0, 0, 0, 0, 0, 0}; // no blocks
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
}
