package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BuildDiff;
import dev.dhanika.rouge.build.BuildDiff.Mismatch;
import dev.dhanika.rouge.build.BuildDiff.Report;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.build.LitematicWriter;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Single source of truth for the active lesson: the full solution, the world
 * anchor it lines up to, and the current difficulty. Orchestrates the user-facing
 * actions ({@code /rouge load}, {@code /rouge solution}, {@code /rouge level}).
 * <p>
 * All methods are called on the main client thread; {@link WorldPlacer} handles the
 * hop to the server thread internally.
 */
public final class LessonManager {

    /** File name written into the Litematica {@code schematics} folder. */
    public static final String OVERLAY_NAME = "rouge_lesson";

    private static BuildSpec solution;
    private static BlockPos anchor;
    private static Level level = Level.BASIC;

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

    /** Loads the bundled sample circuit as the active lesson and writes its overlay. */
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
                + "Overlay written — open Litematica (M) → Load Schematics → " + OVERLAY_NAME + ".");
        ChatDisplay.system("Then: /rouge solution to build it, or /rouge level easy|medium|hard to practice.");
    }

    /** Installs a freshly compiled/loaded solution as the active lesson (basic level). */
    public static void setLesson(BuildSpec spec) {
        setLesson(spec, Level.BASIC);
    }

    /** Installs a solution at a chosen difficulty and writes the overlay once. */
    public static void setLesson(BuildSpec spec, Level startLevel) {
        solution = spec;
        anchor = WorldPlacer.defaultAnchor();
        level = startLevel;
        ProactiveTutor.reset();
        writeOverlay();
    }

    /**
     * A compact description of the active lesson + the learner's current progress,
     * injected into the chat so Rouge can tutor about THIS build. Returns null when
     * no lesson is loaded. Recomputed each call so the diff is always current.
     */
    public static String tutorContext() {
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
            int m = 0;
            for (Mismatch mm : report.wrong()) {
                sb.append("MISTAKE: at ").append(mm.pos().getX()).append(',').append(mm.pos().getY()).append(',')
                        .append(mm.pos().getZ()).append(" placed ").append(mm.found())
                        .append(" but the solution wants ").append(mm.expected()).append('\n');
                if (++m >= 5) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Reports the learner's progress against the solution (local diff, no API). */
    public static void check() {
        if (solution == null) {
            ChatDisplay.error("No lesson loaded. Use /rouge load first.");
            return;
        }
        if (anchor == null) {
            ChatDisplay.error("No build anchor yet — load a lesson first.");
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

    /** Switches difficulty and rewrites the overlay locally (no AI call). */
    public static void setLevel(Level newLevel) {
        if (solution == null) {
            ChatDisplay.error("No lesson loaded. Use /rouge load first.");
            return;
        }
        level = newLevel;
        writeOverlay();
        int shown = Difficulty.shown(solution, level).size();
        int total = solution.blocks().size();
        ChatDisplay.system("Level " + level.lower() + ": overlay shows " + shown + " of " + total
                + " blocks — build the rest yourself. Reload it in Litematica (M → Load).");
    }

    /** Places the full solution into the world (the answer key). */
    public static void placeSolution() {
        if (solution == null) {
            ChatDisplay.error("No lesson loaded. Use /rouge load first.");
            return;
        }
        if (anchor == null) {
            anchor = WorldPlacer.defaultAnchor();
        }
        if (!WorldPlacer.isAvailable()) {
            ChatDisplay.error("Can only place blocks in singleplayer. The overlay still works on servers.");
            return;
        }
        WorldPlacer.place(solution.blocks(), anchor);
        ChatDisplay.system("Built the full solution in front of you.");
    }

    /** Writes the overlay {@code .litematic} for the current level. (M2 narrows the subset.) */
    private static void writeOverlay() {
        if (solution == null) {
            return;
        }
        List<BlockEntry> shown = Difficulty.shown(solution, level);
        try {
            Path file = schematicsDir().resolve(OVERLAY_NAME + ".litematic");
            LitematicWriter.write(OVERLAY_NAME, shown,
                    solution.sizeX(), solution.sizeY(), solution.sizeZ(), file);
        } catch (Exception e) {
            ChatDisplay.error("Failed to write the overlay: " + e.getMessage());
        }
    }

    private static String shortName(String id) {
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }

    private static Path schematicsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
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
