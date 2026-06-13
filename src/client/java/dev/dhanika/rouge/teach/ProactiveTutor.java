package dev.dhanika.rouge.teach;

import dev.dhanika.rouge.build.BuildDiff;
import dev.dhanika.rouge.build.BuildDiff.Mismatch;
import dev.dhanika.rouge.build.BuildDiff.Report;
import dev.dhanika.rouge.build.BuildSpec;
import dev.dhanika.rouge.chat.ChatDisplay;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Watches the player's build and quietly points out mistakes — entirely locally
 * (a block-diff every couple of seconds), so it never hits the API or rate-limits.
 * <p>
 * It speaks only on a <b>new</b> wrong placement (deduped per position, re-armed
 * once corrected) and at most once per scan, so it nudges rather than nags.
 */
public final class ProactiveTutor {

    /** ~2 seconds between scans. */
    private static final int SCAN_INTERVAL_TICKS = 40;

    private static int ticks;
    private static final Set<Long> reported = new HashSet<>();
    private static boolean wasComplete;

    private ProactiveTutor() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ProactiveTutor::onTick);
    }

    private static void onTick(Minecraft client) {
        if (++ticks < SCAN_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;

        BuildSpec solution = LessonManager.solution();
        BlockPos anchor = LessonManager.anchor();
        if (solution == null || anchor == null || client.level == null || client.player == null) {
            return;
        }

        Report report = BuildDiff.compute(solution, anchor);

        // Re-arm positions that were fixed so a future mistake there warns again.
        Set<Long> currentWrong = new HashSet<>();
        for (Mismatch m : report.wrong()) {
            currentWrong.add(m.pos().asLong());
        }
        reported.retainAll(currentWrong);

        // One nudge per scan, on the first not-yet-reported wrong block.
        for (Mismatch m : report.wrong()) {
            if (reported.add(m.pos().asLong())) {
                ChatDisplay.system("Heads up — " + shortName(m.found()) + " at "
                        + posStr(m.pos()) + ", but the solution wants " + shortName(m.expected()) + " there.");
                break;
            }
        }

        // Congratulate once on completion.
        boolean complete = report.isComplete() && report.correct() > 0;
        if (complete && !wasComplete) {
            ChatDisplay.system("Nice — that matches the solution. ✔");
        }
        wasComplete = complete;
    }

    /** Clears tutor state (e.g. on a new lesson or disconnect). */
    public static void reset() {
        reported.clear();
        wasComplete = false;
        ticks = 0;
    }

    private static String shortName(String id) {
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }

    private static String posStr(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
