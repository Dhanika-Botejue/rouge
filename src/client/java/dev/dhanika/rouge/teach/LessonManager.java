package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BuildDiff;
import dev.dhanika.rouge.build.BuildDiff.Mismatch;
import dev.dhanika.rouge.build.BuildDiff.Report;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.build.SignalTracer;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.render.GhostRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for the active lesson: the full solution, the world anchor it lines
 * up to, and the current difficulty. Holds the state that {@link BuildDiff}, {@link
 * ProactiveTutor}, and the {@code /rouge solution|check|level} actions read.
 * <p>
 * The build is shown as a translucent in-world hologram via {@link GhostRenderer}; difficulty
 * controls how much of it is shown (easy shows everything, medium/hard hide a fraction the
 * learner must figure out and place themselves). Step-by-step builds are driven by {@link
 * StepSession}, which renders each step and registers it here as the diff target via
 * {@link #setActive}.
 * <p>
 * All methods are called on the main client thread; {@link WorldPlacer} handles the hop to the
 * server thread internally.
 */
public final class LessonManager {

    private static BuildSpec solution;
    private static BlockPos anchor;
    private static Level level = Level.EASY;

    private LessonManager() {
    }

    public static BuildSpec solution() {
        return solution;
    }

    public static BlockPos anchor() {
        return anchor;
    }

    public static Level level() {
        return level;
    }

    /** Loads the bundled sample circuit as the active lesson and shows its hologram. */
    public static void loadSample() {
        BuildSpec spec;
        try {
            spec = loadSampleResource();
        } catch (Exception e) {
            ChatDisplay.error("Failed to load the sample lesson: " + e.getMessage());
            return;
        }
        setLesson(spec);
        ChatDisplay.system("Loaded a sample lesson (" + spec.blocks().size() + " blocks). "
                + "A translucent hologram is floating in front of you — build solid blocks to match it.");
        ChatDisplay.system("Then: /rouge solution to reveal it, /rouge check for progress, "
                + "or /rouge level easy|medium|hard to practice.");
    }

    /** Installs a freshly loaded solution as the active lesson (easy level) and shows it. */
    public static void setLesson(BuildSpec spec) {
        setLesson(spec, Level.EASY);
    }

    /** Installs a solution at a chosen difficulty, anchored in front of the player, and shows it. */
    public static void setLesson(BuildSpec spec, Level startLevel) {
        solution = spec;
        anchor = WorldPlacer.defaultAnchor();
        level = startLevel;
        ProactiveTutor.reset();
        renderSolution();
    }

    /**
     * Registers the current step of a step-by-step build as the active lesson (the diff target
     * for {@link ProactiveTutor}/{@code /rouge check}), pinned to a caller-supplied {@code
     * stepAnchor} so the build keeps a single stable origin as steps advance. Does <b>not</b>
     * render — {@link StepSession} draws the step (with its new-block highlight) itself.
     */
    public static void setActive(BuildSpec stepSolution, BlockPos stepAnchor, Level activeLevel) {
        solution = stepSolution;
        anchor = stepAnchor;
        level = activeLevel;
        ProactiveTutor.reset();
    }

    /** Clears the active lesson and the hologram. */
    public static void clearLesson() {
        solution = null;
        anchor = null;
        GhostRenderer.clear();
        ProactiveTutor.reset();
    }

    /**
     * A compact description of the active lesson + the learner's current progress,
     * injected into the chat so Rouge can tutor about THIS build. Returns null when
     * no lesson is loaded. Recomputed each call so the diff is always current.
     */
    public static String tutorContext() {
        return tutorContext(null);
    }

    /**
     * Same as {@link #tutorContext()} but ranks the player's mistakes by how closely each
     * matches what they just described ({@code playerQuery}) — the component they named, the
     * block they're looking at, and proximity to them — so the most relevant mismatch is
     * flagged PRIMARY and the AI fixes THAT one rather than an arbitrary mismatch.
     */
    public static String tutorContext(String playerQuery) {
        if (solution == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ACTIVE LESSON (difficulty ").append(level.lower()).append("). The player is building this ")
                .append("redstone circuit in front of them. Help them learn — give hints and point out mistakes; ")
                .append("reveal the full answer only if they explicitly ask.\n");
        sb.append("SOLUTION (answer key):\n");
        int n = 0;
        for (BlockEntry b : solution.blocks()) {
            sb.append("- ").append(b.block()).append(" @").append(b.x()).append(',').append(b.y()).append(',')
                    .append(b.z()).append(" [").append(b.role()).append("]\n");
            if (++n >= 40) {
                sb.append("- …(truncated)\n");
                break;
            }
        }
        if (anchor != null) {
            Report report = BuildDiff.compute(solution, anchor);
            sb.append("PROGRESS: ").append(report.correct()).append('/').append(report.total())
                    .append(" correct, ").append(report.wrong().size()).append(" wrong, ")
                    .append(report.missing()).append(" still to place.\n");
            List<Mismatch> ranked = rankByRelevance(report.wrong(), playerQuery);
            int m = 0;
            for (Mismatch mm : ranked) {
                sb.append(m == 0
                        ? "PRIMARY MISTAKE (closest to what the player described — fix THIS one): at "
                        : "MISTAKE: at ");
                sb.append(mm.pos().getX()).append(',').append(mm.pos().getY()).append(',')
                        .append(mm.pos().getZ()).append(" placed ").append(mm.found())
                        .append(" but the solution wants ").append(mm.expected())
                        .append(" — fix it by replacing that block with ").append(mm.expected())
                        .append(" (use the solution's exact block; never swap in a redstone_block for "
                                + "redstone_wire/dust).\n");
                if (++m >= 5) {
                    break;
                }
            }
            if (!ranked.isEmpty()) {
                sb.append("When you emit a rougefix, correct the PRIMARY MISTAKE first and use the EXACT block "
                        + "the solution lists for that position.\n");
            }
        }
        return sb.toString();
    }

    /** Tokens too generic to indicate which component the player meant. */
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "redstone", "minecraft", "block", "wall", "floor");

    /**
     * Orders mismatches most-relevant-first. Relevance combines: whether the player's message
     * named the block involved (strongest), how near the mismatch is to the block under their
     * crosshair, and how near it is to the player (tiebreak).
     */
    private static List<Mismatch> rankByRelevance(List<Mismatch> wrong, String query) {
        if (wrong.size() <= 1) {
            return wrong;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockPos look = (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
                ? bhr.getBlockPos() : null;
        BlockPos eye = mc.player != null ? mc.player.blockPosition() : null;
        List<Mismatch> sorted = new ArrayList<>(wrong);
        sorted.sort(Comparator.comparingDouble((Mismatch mm) -> -relevanceScore(mm, query, look, eye)));
        return sorted;
    }

    private static double relevanceScore(Mismatch mm, String query, BlockPos look, BlockPos eye) {
        double score = 0;
        if (queryMentions(query, mm.expected()) || queryMentions(query, mm.found())) {
            score += 1000; // the player named this component
        }
        if (look != null) {
            double d = Math.sqrt(look.distSqr(mm.pos()));
            if (d <= 1.5) {
                score += 500; // their crosshair is on the broken block
            }
            score += Math.max(0, 50 - d * 5); // nearer the crosshair ranks higher
        }
        if (eye != null) {
            score += Math.max(0, 30 - Math.sqrt(eye.distSqr(mm.pos()))); // nearer the player (tiebreak)
        }
        return score;
    }

    /** True when {@code query} appears to name the component identified by {@code blockId}. */
    private static boolean queryMentions(String query, String blockId) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT);
        for (String tok : shortName(blockId).split("[ _]")) {
            if (tok.length() >= 3 && !GENERIC_TOKENS.contains(tok) && q.contains(tok)) {
                return true;
            }
        }
        // Common synonyms players use that don't appear verbatim in the block id.
        if (blockId.contains("redstone_wire") && (q.contains("dust") || q.contains("wire"))) return true;
        if (blockId.contains("redstone_lamp") && (q.contains("lamp") || q.contains("light"))) return true;
        if (blockId.contains("redstone_torch") && q.contains("torch")) return true;
        if (blockId.contains("redstone_block") && q.contains("redstone block")) return true;
        return false;
    }

    /**
     * Full diagnostic context for a "why isn't this working?" question: the tutor context
     * (solution + progress) combined with a live signal trace from {@link SignalTracer}.
     * Returns null when no lesson is loaded.
     */
    public static String debugContext() {
        if (solution == null || anchor == null) return null;
        String tutor = tutorContext();
        String trace = SignalTracer.trace(solution, anchor);
        if (tutor == null) return null;
        return trace.isBlank() ? tutor : tutor + "\n" + trace;
    }

    /** Reports the learner's progress against the solution (local diff, no API). */
    public static void check() {
        if (solution == null) {
            ChatDisplay.error("No active build. Ask Rouge to teach you something, or /rouge load a sample.");
            return;
        }
        if (anchor == null) {
            ChatDisplay.error("No build anchor yet — start a build first.");
            return;
        }
        Report report = BuildDiff.compute(solution, anchor);
        ChatDisplay.system("Progress: " + report.correct() + "/" + report.total() + " correct, "
                + report.wrong().size() + " wrong, " + report.missing() + " still to place.");
        int shown = 0;
        for (Mismatch m : report.wrong()) {
            ChatDisplay.system(" • " + m.pos().getX() + "," + m.pos().getY() + "," + m.pos().getZ()
                    + ": expected " + shortName(m.expected()) + ", found " + shortName(m.found()));
            if (++shown >= 5) {
                break;
            }
        }
        if (report.isComplete()) {
            ChatDisplay.system("Complete — your build matches the solution! ✔");
        }
    }

    /**
     * Switches difficulty and refreshes the hologram (no AI call). During a step-by-step build
     * this re-renders the current step at the new level; for a standalone lesson it re-renders
     * the whole solution.
     */
    public static void setLevel(Level newLevel) {
        if (solution == null) {
            ChatDisplay.error("No active build. Ask Rouge to teach you something, or /rouge load a sample.");
            return;
        }
        level = newLevel;
        if (StepSession.isActive()) {
            StepSession.refresh();
        } else {
            renderSolution();
        }
        int shownCount = Difficulty.shown(solution, level).size();
        int total = solution.blocks().size();
        ChatDisplay.system("Level " + level.lower() + ": hologram shows " + shownCount + " of " + total
                + " blocks — build the rest yourself.");
    }

    /** Places the full solution into the world (the answer key). */
    public static void placeSolution() {
        // When a step-by-step build is active, complete it all at once.
        if (StepSession.isActive()) {
            StepSession.completeAll();
            return;
        }
        if (solution == null) {
            ChatDisplay.error("No active build. Ask Rouge to teach you something, or /rouge load a sample.");
            return;
        }
        if (anchor == null) {
            anchor = WorldPlacer.defaultAnchor();
        }
        if (!WorldPlacer.isAvailable()) {
            ChatDisplay.error("Can only place blocks in singleplayer.");
            return;
        }
        WorldPlacer.place(solution.blocks(), anchor);
        ChatDisplay.system("Built the full solution in front of you.");
    }

    /** Renders the difficulty-filtered solution as a translucent hologram (all shown blocks highlighted). */
    private static void renderSolution() {
        if (solution == null || anchor == null) {
            return;
        }
        List<BuildSpec.BlockEntry> shown = Difficulty.shown(solution, level);
        var entries = GhostRenderer.fromSpec(shown);
        GhostRenderer.show(anchor, entries, entries);
    }

    private static String shortName(String id) {
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }

    private static BuildSpec loadSampleResource() throws Exception {
        try (InputStream in = LessonManager.class.getResourceAsStream("/rouge/sample_solution.json")) {
            if (in == null) {
                throw new IllegalStateException("sample_solution.json not found on classpath");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return BuildSpec.fromJson(json);
        }
    }
}
