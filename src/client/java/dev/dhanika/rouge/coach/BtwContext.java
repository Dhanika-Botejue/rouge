package dev.dhanika.rouge.coach;

import dev.dhanika.rouge.build.BlockNames;
import dev.dhanika.rouge.build.BuildDiff;
import dev.dhanika.rouge.build.BuildDiff.Mismatch;
import dev.dhanika.rouge.build.BuildDiff.Report;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.render.GhostRenderer;
import dev.dhanika.rouge.teach.LessonManager;
import dev.dhanika.rouge.teach.StepSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a rich system-context message for BTW coaching: live build state, hologram layout,
 * world diff, and redstone neighborhood so the model can answer in parallel with the hologram.
 */
public final class BtwContext {

    private static final int MAX_LIST = 40;
    private static final int MAX_NEIGHBORS = 12;
    private static final int MAX_PREVIEW_BLOCKS = 80;

    private BtwContext() {
    }

    /** Premium context for an active step-by-step build. */
    public static String build() {
        if (!StepSession.isActive()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PREMIUM BTW CONTEXT — mid-build coaching (hologram + chat in parallel).\n");
        sb.append("The player sees a translucent hologram in-world RIGHT NOW. Your reply is chat-only.\n\n");

        appendBuildOverview(sb);
        appendStepTimeline(sb);
        appendCurrentStepDeepDive(sb);
        appendHologramState(sb);
        appendWorldSnapshot(sb);

        BuildSpec spec = LessonManager.solution();
        BlockPos anchor = LessonManager.anchor();
        if (spec != null && anchor != null) {
            appendPlacements(sb, spec, anchor);
            appendGapsAndMistakes(sb, spec, anchor);
            appendRedstoneNeighborhood(sb, spec, anchor);
        }

        sb.append("\nCOACHING REMINDER: intro → digestible technical body → conclusion tied to this build.\n");
        sb.append("Do NOT emit build/planning fences. Hologram stays visible while you answer.\n");
        return sb.toString().trim();
    }

    /** Context when /btw is used with no active step build or preview hologram. */
    public static String general() {
        StringBuilder sb = new StringBuilder();
        sb.append("PREMIUM BTW CONTEXT — open coaching (no active step build).\n");
        sb.append("Player used /btw. Nothing is being diffed; they may not have placed any blocks yet.\n\n");

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            BlockPos feet = player.blockPosition();
            sb.append("PLAYER: world ").append(feet.getX()).append(',').append(feet.getY())
                    .append(',').append(feet.getZ())
                    .append(", facing ").append(player.getDirection().name().toLowerCase()).append('\n');
        }

        sb.append("HOLOGRAM: step=").append(GhostRenderer.isStepActive() ? "visible" : "none");
        sb.append(", preview=").append(GhostRenderer.isPreviewActive() ? "visible" : "none").append('\n');

        BuildSpec spec = LessonManager.solution();
        BlockPos anchor = LessonManager.anchor();
        if (spec != null && anchor != null) {
            sb.append("NOTE: A lesson spec is loaded but no step session is active.\n");
            appendWorldSnapshot(sb);
            appendPlacements(sb, spec, anchor);
        } else {
            sb.append("BUILD STATE: no Rouge lesson or build anchor loaded.\n");
        }

        sb.append("\nCOACHING REMINDER: intro → digestible technical body → conclusion.\n");
        sb.append("Answer the redstone question fully — do NOT tell them to start a build first.\n");
        sb.append("Do NOT emit build/planning fences unless they explicitly ask to build something.\n");
        return sb.toString().trim();
    }

    /** Premium context for gold preview review (before step-by-step build). */
    public static String forPreview(StepPlan plan) {
        if (plan == null || plan.steps().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PREMIUM BTW CONTEXT — gold preview review (hologram + chat in parallel).\n");
        sb.append("Player sees the FULL design as a gold-outlined preview hologram. Not placing step-by-step yet.\n\n");

        sb.append("BUILD: ").append(plan.circuit());
        sb.append(" | ").append(plan.steps().size()).append(" planned steps\n");

        List<StepPlan.Step> steps = plan.steps();
        sb.append("STEP PLAN (full roadmap):\n");
        for (int i = 0; i < steps.size(); i++) {
            StepPlan.Step s = steps.get(i);
            sb.append(i + 1).append(". ").append(s.title());
            if (!s.explanation().isBlank()) {
                sb.append(" — ").append(s.explanation());
            }
            sb.append(" (").append(s.blocks().size()).append(" cumulative blocks)\n");
        }

        List<dev.dhanika.rouge.build.BlockEntry> blocks = steps.get(steps.size() - 1).blocks();
        sb.append("\nPREVIEW HOLOGRAM — complete block map (build-local x,y,z):\n");
        appendBlockInventory(sb, blocks, MAX_PREVIEW_BLOCKS);

        sb.append("\nREDSTONE INVENTORY (counts in full preview):\n");
        appendComponentCounts(sb, blocks);

        sb.append("\nCOACHING REMINDER: intro → digestible technical body → conclusion tied to preview.\n");
        sb.append("Explain how parts connect using coords above. Do NOT emit build/planning fences.\n");
        return sb.toString().trim();
    }

    private static void appendBuildOverview(StringBuilder sb) {
        sb.append("BUILD OVERVIEW\n");
        sb.append("- Circuit: ").append(StepSession.circuitName()).append('\n');
        sb.append("- Progress: step ").append(StepSession.stepNumber())
                .append(" of ").append(StepSession.stepTotal()).append('\n');
        Level diff = LessonManager.level();
        sb.append("- Difficulty: ").append(diff.lower())
                .append(" (controls how many blocks the hologram hides)\n");
        BlockPos anchor = LessonManager.anchor();
        if (anchor != null) {
            sb.append("- World anchor (build origin): ")
                    .append(anchor.getX()).append(',').append(anchor.getY()).append(',').append(anchor.getZ())
                    .append('\n');
        }
        String footprint = StepSession.worldFootprintLine();
        if (!footprint.isBlank()) {
            sb.append("- ").append(footprint).append('\n');
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && anchor != null) {
            BlockPos feet = player.blockPosition();
            sb.append("- Player at world ").append(feet.getX()).append(',').append(feet.getY())
                    .append(',').append(feet.getZ())
                    .append(", facing ").append(player.getDirection().name().toLowerCase()).append('\n');
        }
        sb.append("- Hologram on screen: step blocks=")
                .append(GhostRenderer.isStepActive() ? "yes" : "no")
                .append(", preview=").append(GhostRenderer.isPreviewActive() ? "yes" : "no").append('\n');
        sb.append('\n');
    }

    private static void appendStepTimeline(StringBuilder sb) {
        sb.append("STEP TIMELINE\n");
        int current = StepSession.stepNumber();
        int total = StepSession.stepTotal();
        List<String> done = StepSession.completedStepTitles();
        for (int i = 0; i < done.size(); i++) {
            sb.append(i + 1).append(". [DONE] ").append(done.get(i)).append('\n');
        }
        sb.append(current).append(". [NOW] ").append(StepSession.currentStepTitle()).append('\n');
        String next = StepSession.nextStepTitle();
        for (int i = current + 1; i <= total; i++) {
            if (i == current + 1 && !next.isBlank()) {
                sb.append(i).append(". [LATER] ").append(next).append('\n');
            } else if (i > current + 1) {
                sb.append(i).append(". [LATER] (not started)\n");
            }
        }
        sb.append('\n');
    }

    private static void appendCurrentStepDeepDive(StringBuilder sb) {
        sb.append("CURRENT STEP — deep dive\n");
        sb.append("- Title: ").append(StepSession.currentStepTitle()).append('\n');
        String expl = StepSession.currentStepExplanation();
        if (!expl.isBlank()) {
            sb.append("- Teaching note: ").append(expl).append('\n');
        }
        List<dev.dhanika.rouge.build.BlockEntry> cumulative = StepSession.cumulativeBlocksNow();
        List<dev.dhanika.rouge.build.BlockEntry> added = StepSession.newBlocksThisStep();
        sb.append("- Cumulative blocks this step: ").append(cumulative.size()).append('\n');
        sb.append("- New blocks introduced this step: ").append(added.size()).append('\n');
        sb.append('\n');
    }

    private static void appendHologramState(StringBuilder sb) {
        sb.append("HOLOGRAM STATE (what they see in-world)\n");
        sb.append("- Green glowing outline = place these NOW (new this step, shown by difficulty)\n");
        sb.append("- Blue faint outline = from earlier steps (already part of the design)\n");
        sb.append("- Hidden blocks = on this step but NOT shown in hologram (difficulty)\n");

        BuildSpec spec = LessonManager.solution();
        Level level = LessonManager.level();
        if (spec == null) {
            sb.append('\n');
            return;
        }

        List<BlockEntry> shownSpec = Difficulty.shown(spec, level);
        Set<Long> shownKeys = new HashSet<>();
        for (BlockEntry b : shownSpec) {
            shownKeys.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }

        Set<Long> newKeys = new HashSet<>();
        for (dev.dhanika.rouge.build.BlockEntry b : StepSession.newBlocksThisStep()) {
            newKeys.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }

        List<String> glowNow = new ArrayList<>();
        List<String> priorGlow = new ArrayList<>();
        List<String> hidden = new ArrayList<>();

        for (BlockEntry b : spec.blocks()) {
            long key = BlockPos.asLong(b.x(), b.y(), b.z());
            String line = formatBlockLine(b.block(), b.x(), b.y(), b.z(), b.role());
            if (!shownKeys.contains(key)) {
                hidden.add(line);
            } else if (newKeys.contains(key)) {
                glowNow.add(line);
            } else {
                priorGlow.add(line);
            }
        }

        sb.append("\nGLOW NOW (green — priority placements):\n");
        appendLines(sb, glowNow, MAX_LIST, "- (none visible)\n");

        sb.append("PRIOR HOLOGRAM (blue — context from earlier steps):\n");
        appendLines(sb, priorGlow, MAX_LIST, "- (none)\n");

        sb.append("HIDDEN THIS STEP (not in hologram — player must infer):\n");
        appendLines(sb, hidden, MAX_LIST, "- (none hidden at this difficulty)\n");
        sb.append('\n');
    }

    private static void appendWorldSnapshot(StringBuilder sb) {
        BuildSpec spec = LessonManager.solution();
        BlockPos anchor = LessonManager.anchor();
        if (spec == null || anchor == null) return;

        Report report = BuildDiff.compute(spec, anchor);
        sb.append("WORLD SNAPSHOT (solid blocks vs solution)\n");
        sb.append("- Correct: ").append(report.correct()).append('/').append(report.total()).append('\n');
        sb.append("- Still missing: ").append(report.missing()).append('\n');
        sb.append("- Wrong block type: ").append(report.wrong().size()).append('\n');
        sb.append("- Step complete: ").append(report.isComplete() ? "yes" : "no").append('\n');
        sb.append('\n');
    }

    private static void appendPlacements(StringBuilder sb, BuildSpec spec, BlockPos anchor) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        sb.append("CORRECT PLACEMENTS (player built these solid — matches solution):\n");
        int shown = 0;
        for (BlockEntry b : spec.blocks()) {
            String expected = BuildSpec.baseId(b.block());
            BlockPos pos = anchor.offset(b.x(), b.y(), b.z());
            String found = idOf(level.getBlockState(pos));
            if (found.equals(expected)) {
                sb.append("- ").append(BlockNames.shortName(expected))
                        .append(" @ world ").append(pos.getX()).append(',').append(pos.getY())
                        .append(',').append(pos.getZ())
                        .append(" (local ").append(b.x()).append(',').append(b.y()).append(',')
                        .append(b.z()).append(") [").append(b.role()).append("]\n");
                if (++shown >= MAX_LIST) {
                    sb.append("- …\n");
                    break;
                }
            }
        }
        if (shown == 0) {
            sb.append("- (nothing placed correctly yet on this step)\n");
        }
        sb.append('\n');
    }

    private static void appendGapsAndMistakes(StringBuilder sb, BuildSpec spec, BlockPos anchor) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        Report report = BuildDiff.compute(spec, anchor);
        Set<Long> wrongKeys = new HashSet<>();
        for (Mismatch mm : report.wrong()) {
            wrongKeys.add(mm.pos().asLong());
        }

        sb.append("STILL TO PLACE (solution expects a block, player has not matched yet):\n");
        int missing = 0;
        for (BlockEntry b : spec.blocks()) {
            BlockPos pos = anchor.offset(b.x(), b.y(), b.z());
            if (wrongKeys.contains(pos.asLong())) continue;
            String expected = BuildSpec.baseId(b.block());
            String found = idOf(level.getBlockState(pos));
            if (!found.equals(expected)) {
                sb.append("- needs ").append(BlockNames.shortName(expected))
                        .append(" @ world ").append(pos.getX()).append(',').append(pos.getY())
                        .append(',').append(pos.getZ())
                        .append(" (local ").append(b.x()).append(',').append(b.y()).append(',')
                        .append(b.z()).append(") [").append(b.role()).append("]")
                        .append(" — currently ").append(BlockNames.shortName(found)).append('\n');
                if (++missing >= MAX_LIST) {
                    sb.append("- …\n");
                    break;
                }
            }
        }
        if (missing == 0) {
            sb.append("- (no gaps — all expected blocks match)\n");
        }

        if (!report.wrong().isEmpty()) {
            sb.append("\nMISTAKES (wrong block type at position):\n");
            int m = 0;
            for (Mismatch mm : report.wrong()) {
                sb.append("- @ world ").append(mm.pos().getX()).append(',')
                        .append(mm.pos().getY()).append(',').append(mm.pos().getZ())
                        .append(": has ").append(BlockNames.shortName(mm.found()))
                        .append(", needs ").append(BlockNames.shortName(mm.expected())).append('\n');
                if (++m >= MAX_LIST) {
                    sb.append("- …\n");
                    break;
                }
            }
        }
        sb.append('\n');
    }

    /** Notes power-relevant blocks and what touches them in the world. */
    private static void appendRedstoneNeighborhood(StringBuilder sb, BuildSpec spec, BlockPos anchor) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        sb.append("REDSTONE NEIGHBORHOOD (what touches key components in the world):\n");
        int notes = 0;
        for (BlockEntry b : spec.blocks()) {
            if (!isRedstoneRelevant(b.block())) continue;
            BlockPos pos = anchor.offset(b.x(), b.y(), b.z());
            sb.append("- ").append(BlockNames.shortName(BuildSpec.baseId(b.block())))
                    .append(" @ world ").append(pos.getX()).append(',').append(pos.getY())
                    .append(',').append(pos.getZ());
            sb.append(" [").append(b.role()).append("]: ");
            sb.append(neighborSummary(level, pos));
            sb.append('\n');
            if (++notes >= MAX_NEIGHBORS) {
                sb.append("- …\n");
                break;
            }
        }
        if (notes == 0) {
            sb.append("- (no redstone components on this step yet)\n");
        }
        sb.append('\n');
    }

    private static void appendBlockInventory(StringBuilder sb, List<dev.dhanika.rouge.build.BlockEntry> blocks, int cap) {
        int n = 0;
        for (dev.dhanika.rouge.build.BlockEntry b : blocks) {
            sb.append("- ").append(readableBlock(b.block()))
                    .append(" @ ").append(b.x()).append(',').append(b.y()).append(',').append(b.z())
                    .append(" [").append(inferRole(b.block())).append("]\n");
            if (++n >= cap) {
                sb.append("- … (").append(blocks.size() - cap).append(" more blocks)\n");
                break;
            }
        }
    }

    private static void appendComponentCounts(StringBuilder sb, List<dev.dhanika.rouge.build.BlockEntry> blocks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (dev.dhanika.rouge.build.BlockEntry b : blocks) {
            String role = inferRole(b.block());
            counts.merge(role, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
    }

    private static void appendLines(StringBuilder sb, List<String> lines, int cap, String ifEmpty) {
        if (lines.isEmpty()) {
            sb.append(ifEmpty);
            return;
        }
        int n = 0;
        for (String line : lines) {
            sb.append("- ").append(line).append('\n');
            if (++n >= cap) {
                sb.append("- …\n");
                break;
            }
        }
    }

    private static String formatBlockLine(String blockId, int x, int y, int z, String role) {
        return readableBlock(blockId) + " @ local " + x + ',' + y + ',' + z + " [" + role + "]";
    }

    private static String readableBlock(String blockId) {
        String base = BuildSpec.baseId(blockId);
        String name = BlockNames.shortName(base);
        int bracket = blockId.indexOf('[');
        if (bracket > 0 && blockId.endsWith("]")) {
            return name + " " + blockId.substring(bracket);
        }
        return name;
    }

    private static String inferRole(String blockId) {
        String s = blockId.toLowerCase();
        if (s.contains("redstone_wire")) return "wire";
        if (s.contains("repeater") || s.contains("comparator")) return "delay";
        if (s.contains("lever") || s.contains("button") || s.contains("pressure_plate")
                || s.contains("daylight_detector") || s.contains("tripwire")) return "input";
        if (s.contains("lamp") || s.contains("piston") || s.contains("dispenser") || s.contains("dropper")
                || s.contains("door") || s.contains("note_block") || s.contains("hopper") || s.contains("bell")) {
            return "output";
        }
        if (s.contains("redstone_torch") || s.contains("redstone_block") || s.contains("observer")
                || s.contains("target") || s.contains("slime") || s.contains("honey")) {
            return "component";
        }
        return "support";
    }

    private static boolean isRedstoneRelevant(String blockId) {
        String s = blockId.toLowerCase();
        return s.contains("redstone") || s.contains("repeater") || s.contains("comparator")
                || s.contains("observer") || s.contains("piston") || s.contains("lever")
                || s.contains("button") || s.contains("pressure_plate") || s.contains("target");
    }

    private static String neighborSummary(ClientLevel level, BlockPos pos) {
        StringBuilder nb = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState st = level.getBlockState(n);
            if (st.isAir()) continue;
            String id = idOf(st);
            if (nb.length() > 0) nb.append("; ");
            nb.append(d.name().toLowerCase()).append('=').append(BlockNames.shortName(id));
        }
        return nb.length() == 0 ? "open air around it" : nb.toString();
    }

    private static String idOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
