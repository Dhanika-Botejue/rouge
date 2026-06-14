package dev.dhanika.rouge.render;

import com.mojang.math.Axis;
import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small red cat that sits in the top-left corner of the HUD whenever a Rouge session is
 * active, signalling at a glance that Rouge is listening.
 *
 * <p>When Rouge is thinking (waiting on the AI) the cat bats at a ball of yarn that spins —
 * a livelier, more honest "working…" indicator than the old time-based progress bar, which
 * guessed at completion and stuttered. There is no fake percentage: the cat simply plays
 * until the reply lands.
 */
public final class RougeCatHud {

    // Palette (ARGB).
    private static final int RED       = 0xFFD83A2F; // cat body
    private static final int RED_DARK  = 0xFFA62820; // shading / inner ear shadow
    private static final int EAR_PINK  = 0xFFF2A6A0; // inner ear + nose
    private static final int EYE       = 0xFF1C1C14; // eyes (open)
    private static final int EYE_SHINE = 0xFFFFF6C8; // eye glint
    private static final int YARN      = 0xFFE85A8A; // ball of yarn
    private static final int YARN_LINE = 0xFFF7B6CC; // yarn strands

    // Pixel scale and anchor for the cat (top-left corner).
    private static final int U  = 2;  // px per cat-cell
    private static final int OX = 8;  // origin x
    private static final int OY = 8;  // origin y

    // Cat head bitmap. ' ' transparent, 'X' body, 'D' shaded body, 'P' pink ear, 'E' eye, 'N' nose.
    private static final String[] CAT = {
            " X      X ",
            "XPX    XPX",
            "XXXXXXXXXX",
            "XXXXXXXXXX",
            "XEXXXXXXEX",
            "XXXXNXXXXX",
            "DXXXXXXXXD",
            " XXXXXXXX ",
            " DXXXXXXD ",
    };

    // Thinking state (mirrors the old ThinkingHud lifecycle so call sites barely change).
    private static volatile boolean thinking = false;
    private static volatile long thinkStart = 0L;
    private static volatile long doneTime = -1L;
    private static final long DONE_LINGER_MS = 500;

    private RougeCatHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(RougeCatHud::render);
    }

    /** Rouge started waiting on the AI — the cat begins playing with its yarn. */
    public static void startThinking() {
        thinkStart = System.currentTimeMillis();
        doneTime = -1L;
        thinking = true;
    }

    /** Reply arrived — the cat settles down after a brief beat. */
    public static void stopThinking() {
        doneTime = System.currentTimeMillis();
    }

    private static void render(GuiGraphics g, float tickDelta) {
        // The cat only appears while a Rouge session is open.
        if (!RougeSession.isOpen()) {
            thinking = false;
            doneTime = -1L;
            return;
        }

        long now = System.currentTimeMillis();
        if (thinking && doneTime >= 0 && now - doneTime > DONE_LINGER_MS) {
            thinking = false;
            doneTime = -1L;
        }
        boolean active = thinking;

        drawCat(g, active, now);

        Minecraft mc = Minecraft.getInstance();
        if (active) {
            // Animated "thinking" dots, no fake percentage.
            int dots = (int) ((now / 350) % 4);
            String label = "Rouge is thinking" + ".".repeat(dots);
            g.drawString(mc.font, label, OX, OY + CAT.length * U + 16, 0xFFCCAAFF, true);
            drawYarn(g, now);
        } else {
            g.drawString(mc.font, "Rouge", OX, OY + CAT.length * U + 4, 0xFFCCAAFF, true);
        }
    }

    /** Draws the pixel-art cat. While active the ears twitch and the head bobs a touch. */
    private static void drawCat(GuiGraphics g, boolean active, long now) {
        // Gentle bob while thinking.
        int bob = active ? (int) Math.round(Math.sin(now / 160.0) * 1.5) : 0;

        for (int row = 0; row < CAT.length; row++) {
            String line = CAT[row];
            for (int col = 0; col < line.length(); col++) {
                int color = colorFor(line.charAt(col), active, now);
                if (color == 0) continue;
                int x = OX + col * U;
                int y = OY + row * U + bob;
                g.fill(x, y, x + U, y + U, color);
            }
        }

        // Swishing tail off the cat's right side.
        int tailBaseX = OX + CAT[6].length() * U;
        int tailY = OY + 6 * U + bob;
        double phase = now / (active ? 140.0 : 420.0);
        for (int i = 0; i < 4; i++) {
            int tx = tailBaseX + i * U;
            int ty = tailY + (int) Math.round(Math.sin(phase + i * 0.7) * 2) - i;
            g.fill(tx, ty, tx + U, ty + U, RED);
        }
    }

    private static int colorFor(char c, boolean active, long now) {
        return switch (c) {
            case 'X' -> RED;
            case 'D' -> RED_DARK;
            case 'P' -> EAR_PINK;
            case 'N' -> EAR_PINK;
            case 'E' -> {
                // Blink occasionally; when blinking the eye becomes body-colored.
                boolean blink = !active && (now % 4000) < 150;
                yield blink ? RED : EYE;
            }
            default -> 0;
        };
    }

    /** A ball of yarn the cat bats at — spins while Rouge is thinking. */
    private static void drawYarn(GuiGraphics g, long now) {
        int r = 7;
        int cx = OX + CAT[0].length() * U + r + 6;
        int cy = OY + (CAT.length * U) / 2;
        // Little hop.
        cy += (int) Math.round(Math.abs(Math.sin(now / 200.0)) * -3);

        float angle = (now % 900) / 900f * 360f;

        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));

        // Ball body (a blocky disc).
        for (int dx = -r; dx <= r; dx++) {
            int h = (int) Math.sqrt(r * r - dx * dx);
            g.fill(dx, -h, dx + 1, h, YARN);
        }
        // Wound strands.
        for (int i = -r; i <= r; i += 3) {
            g.fill(-r, i, r, i + 1, YARN_LINE);
        }
        g.fill(-r, -1, r, 1, YARN_LINE);

        pose.popPose();

        // A trailing strand from the yarn back toward the cat's paw.
        g.fill(cx - r - 4, cy + 4, cx - r, cy + 5, YARN_LINE);
    }
}
