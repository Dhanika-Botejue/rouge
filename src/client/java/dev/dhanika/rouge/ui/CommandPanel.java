package dev.dhanika.rouge.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * A slide-in side panel listing every Rouge command and a one-line description, toggled with
 * the {@code Tab} key while a session is open. It renders as a HUD overlay (no screen capture)
 * so the player can keep moving, glance at the reference, and tap {@code Tab} again to dismiss.
 */
public final class CommandPanel {

    // Colors (ARGB).
    private static final int PANEL_BG  = 0xE0121016;
    private static final int BORDER    = 0xFF8A5CFF;
    private static final int TITLE     = 0xFFD9B8FF;
    private static final int HEADER    = 0xFF8FE0A0;
    private static final int CMD_TEXT  = 0xFFFFE08A;
    private static final int DESC_TEXT = 0xFFB8B8C4;
    private static final int HINT_TEXT = 0xFF6E6486;

    private static final int PANEL_W = 232;
    private static final int PAD     = 8;
    private static final int LINE_H  = 11;

    // Slide-in animation.
    private static final long SLIDE_MS = 180;

    /** A single reference row. A {@code header} entry (null command) is a section label. */
    private record Entry(String header, String command, String desc) {
        static Entry header(String h)               { return new Entry(h, null, null); }
        static Entry cmd(String c, String d)        { return new Entry(null, c, d); }
    }

    private static final Entry[] ENTRIES = {
            Entry.header("Session"),
            Entry.cmd("/rouge", "Toggle the chat session on or off"),
            Entry.header("Building"),
            Entry.cmd("/rouge next", "Skip ahead to the next build step"),
            Entry.cmd("/rouge step", "Re-show the current step's hologram"),
            Entry.cmd("/rouge move", "Re-place the hologram where you stand"),
            Entry.cmd("/rouge stop", "Cancel the build and clear the hologram"),
            Entry.cmd("/rouge materials", "Give you this step's blocks (creative)"),
            Entry.cmd("/btw <question>", "Ask about the current build, keep going"),
            Entry.header("Debugging"),
            Entry.cmd("/rouge trace", "Trace live redstone signal around you"),
            Entry.cmd("\"why isn't this working?\"", "Diagnose the fault; say \"fix it\" to apply"),
            Entry.header("Difficulty"),
            Entry.cmd("/rouge level easy", "Show all of each step's blocks"),
            Entry.cmd("/rouge level medium", "Hide 30% — you place those yourself"),
            Entry.cmd("/rouge level hard", "Hide 50% of each step's blocks"),
            Entry.header("Voice & model"),
            Entry.cmd("/rouge voice on|off", "Speak replies aloud (ElevenLabs)"),
            Entry.cmd("/rouge model [id]", "Show or switch the chat model"),
            Entry.header("Lessons"),
            Entry.cmd("/rouge load", "Load the bundled sample lesson"),
            Entry.cmd("/rouge solution", "Place the full answer (singleplayer)"),
            Entry.cmd("/rouge check", "Compare your build against the answer"),
            Entry.header("Tip"),
            Entry.cmd("just talk", "Say \"build a T flip-flop\", \"yes\", \"next\"…"),
    };

    private static volatile boolean visible = false;
    private static volatile long toggledAt = 0L;
    private static boolean prevTabDown = false;

    private CommandPanel() {}

    /** True while the panel is open — used to suppress the vanilla player list (also on Tab). */
    public static boolean isVisible() {
        return visible;
    }

    public static void register() {
        HudRenderCallback.EVENT.register(CommandPanel::render);
        ClientTickEvents.END_CLIENT_TICK.register(CommandPanel::tick);
    }

    /** Edge-detects Tab presses and toggles the panel — only while a session is open and no screen is up. */
    private static void tick(Minecraft mc) {
        if (!RougeSession.isOpen()) {
            visible = false;
            prevTabDown = false;
            return;
        }
        long window = mc.getWindow().getWindow();
        boolean tabDown = mc.screen == null
                && InputConstants.isKeyDown(window, GLFW.GLFW_KEY_TAB);
        if (tabDown && !prevTabDown) {
            visible = !visible;
            toggledAt = System.currentTimeMillis();
        }
        prevTabDown = tabDown;
    }

    private static void render(GuiGraphics g, float tickDelta) {
        if (!RougeSession.isOpen()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        long now = System.currentTimeMillis();
        long since = now - toggledAt;
        float t = Math.min(1f, since / (float) SLIDE_MS);
        // Ease in/out so the slide feels smooth either direction.
        float eased = visible ? ease(t) : ease(1f - t);
        if (!visible && since >= SLIDE_MS) return; // fully retracted — draw nothing

        int panelH = PAD * 2 + ENTRIES.length * LINE_H + LINE_H + 6;
        panelH = Math.min(panelH, screenH - 8);
        int panelTop = (screenH - panelH) / 2;

        // Slide from off the right edge to flush against it.
        int hidden = PANEL_W + 6;
        int x = screenW - (int) (eased * hidden);
        int x2 = x + PANEL_W;

        // Raise the whole panel above other HUD layers (player list, scoreboard, etc.).
        // Vanilla HUD draws around z=0; tooltips use z=400. Sitting at z=400 keeps the panel
        // on top regardless of draw order so nothing covers it.
        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400);

        g.fill(x, panelTop, x2, panelTop + panelH, PANEL_BG);
        // Left accent border.
        g.fill(x, panelTop, x + 2, panelTop + panelH, BORDER);

        int tx = x + PAD;
        int y = panelTop + PAD;

        g.drawString(font, "Rouge — Commands", tx, y, TITLE, true);
        y += LINE_H + 3;

        for (Entry e : ENTRIES) {
            if (y + LINE_H > panelTop + panelH - LINE_H) break; // clip to panel
            if (e.header() != null) {
                g.drawString(font, e.header(), tx, y, HEADER, false);
            } else {
                int cmdW = font.width(e.command());
                g.drawString(font, e.command(), tx, y, CMD_TEXT, false);
                String desc = "— " + e.desc();
                String fit = font.plainSubstrByWidth(desc, PANEL_W - PAD * 2 - cmdW - 6);
                g.drawString(font, fit, tx + cmdW + 6, y, DESC_TEXT, false);
            }
            y += LINE_H;
        }

        g.drawString(font, "Press Tab to close", tx, panelTop + panelH - LINE_H - 1, HINT_TEXT, false);

        pose.popPose();
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t); // smoothstep
    }
}
