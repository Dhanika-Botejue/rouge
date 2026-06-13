package dev.dhanika.rouge.ui;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.CircuitLibrary;
import dev.dhanika.rouge.build.CircuitPrimitive;
import dev.dhanika.rouge.session.RougeSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The build browser: a visual selection window over Rouge's library of buildable circuits.
 * Each match is shown as a clickable tile with an isometric preview rendered from its block
 * data, its name and stats, and a description. Click tiles to pick the parts you want and hit
 * "Stitch selected", or hit "Let Rouge choose" to defer to the AI.
 *
 * <p>Selections persist as the search filter changes, so the player can pull parts from several
 * different queries into a single stitch.
 */
public final class CircuitBrowserScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private static final int MARGIN = 16;
    private static final int ROW_H = 46;
    private static final int THUMB = 38;

    // Colors (ARGB).
    private static final int PANEL_BG    = 0xC0101014;
    private static final int ROW_BG      = 0x40000000;
    private static final int ROW_HOVER   = 0x33FFFFFF;
    private static final int SEL_ROW     = 0x4055FF66;
    private static final int SEL_BORDER  = 0xFF55FF66;
    private static final int THUMB_BG    = 0xFF26262E;
    private static final int TITLE_TEXT  = 0xFFFFFFFF;
    private static final int META_TEXT   = 0xFF9AE0A0;
    private static final int DESC_TEXT   = 0xFF9C9CA8;
    private static final int SUBTITLE    = 0xFFD0B0FF;
    private static final int SCROLL_BAR  = 0xFFB070FF;
    private static final int CHECK_GREEN = 0xFF55FF66;
    private static final int CHECK_EMPTY = 0xFF55555E;

    private final Set<String> selected = new LinkedHashSet<>();
    private String query;

    private EditBox searchBox;
    private Button stitchButton;
    private List<CircuitPrimitive> filtered = List.of();

    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int scrollOffset;
    private boolean firstRenderLogged = false;

    public CircuitBrowserScreen(String query) {
        super(Component.literal("Rouge — Pick Parts to Build"));
        this.query = query == null ? "" : query;
    }

    /** Opens the browser on the client thread, seeded with a search query. */
    public static void open(String query) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new CircuitBrowserScreen(query)));
    }

    @Override
    protected void init() {
        int top = 40;
        searchBox = new EditBox(font, MARGIN, top, width - 2 * MARGIN, 18, Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search builds — e.g. \"piston door\", \"clock\"…"));
        searchBox.setValue(query);
        searchBox.setResponder(this::refilter);
        addRenderableWidget(searchBox);

        listLeft = MARGIN;
        listRight = width - MARGIN;
        listTop = top + 24;
        listBottom = height - 38;

        refilter(query);

        int gap = 8;
        int btnW = (width - 2 * MARGIN - 2 * gap) / 3;
        int by = height - 28;
        int x0 = MARGIN;
        stitchButton = Button.builder(stitchLabel(), b -> onStitch())
                .bounds(x0, by, btnW, 20).build();
        addRenderableWidget(stitchButton);
        addRenderableWidget(Button.builder(Component.literal("Let Rouge choose"), b -> onLetRougeChoose())
                .bounds(x0 + btnW + gap, by, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x0 + 2 * (btnW + gap), by, btnW, 20).build());
        updateStitchButton();
        setInitialFocus(searchBox);
        LOGGER.info("[Rouge] Build browser opened: buildMode={}, query='{}', entries={}, size={}x{}, list=[{}..{} x {}..{}]",
                RougeSession.getBuildMode(), query, filtered.size(), width, height,
                listLeft, listRight, listTop, listBottom);
    }

    private void refilter(String q) {
        this.query = q;
        filtered = CircuitLibrary.rankedBuildable(q);
        clampScroll();
    }

    private void onStitch() {
        List<CircuitPrimitive> parts = new ArrayList<>();
        for (String id : selected) {
            CircuitPrimitive p = CircuitLibrary.get(id);
            if (p != null && p.isBuildable()) parts.add(p);
        }
        if (parts.isEmpty()) return;
        String goal = searchBox.getValue();
        LOGGER.info("[Rouge] Stitch requested: {} part(s) {} (buildMode={}, goal='{}')",
                parts.size(), parts.stream().map(CircuitPrimitive::id).toList(),
                RougeSession.getBuildMode(), goal);
        onClose();
        RougeSession.stitchSelected(goal, parts);
    }

    /** Hand the current goal back to the AI to pick and build itself (the classic flow). */
    private void onLetRougeChoose() {
        String goal = searchBox.getValue();
        LOGGER.info("[Rouge] Deferring to AI to pick & build (buildMode={}, goal='{}')",
                RougeSession.getBuildMode(), goal);
        onClose();
        RougeSession.buildWithAi(goal);
    }

    private void toggle(String id) {
        boolean nowSelected = !selected.remove(id);
        if (nowSelected) selected.add(id);
        updateStitchButton();
        LOGGER.info("[Rouge] Browser toggle: {} -> {} (selected now: {})", id, nowSelected, selected);
    }

    private void updateStitchButton() {
        stitchButton.setMessage(stitchLabel());
        stitchButton.active = !selected.isEmpty();
    }

    private Component stitchLabel() {
        return Component.literal("Stitch selected (" + selected.size() + ")");
    }

    // --- rendering ---

    @Override
    public void removed() {
        LOGGER.info("[Rouge] Build browser REMOVED/closed (was current screen = {})",
                Minecraft.getInstance().screen == this);
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!firstRenderLogged) {
            firstRenderLogged = true;
            LOGGER.info("[Rouge] Build browser FIRST RENDER (current screen = this: {})",
                    Minecraft.getInstance().screen == this);
        }
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, TITLE_TEXT);
        g.drawCenteredString(font,
                Component.literal("Click tiles to pick parts to merge — or \"Let Rouge choose\" for you."),
                width / 2, 26, SUBTITLE);

        renderList(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick); // search box + buttons on top
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(listLeft, listTop, listRight, listBottom, PANEL_BG);

        if (filtered.isEmpty()) {
            g.drawCenteredString(font, Component.literal("No matching builds — try a different search."),
                    (listLeft + listRight) / 2, (listTop + listBottom) / 2 - 4, META_TEXT);
            return;
        }

        g.enableScissor(listLeft, listTop, listRight, listBottom);
        int y = listTop - scrollOffset;
        for (CircuitPrimitive p : filtered) {
            if (y + ROW_H >= listTop && y <= listBottom) {
                renderRow(g, p, y, mouseX, mouseY);
            }
            y += ROW_H;
        }
        g.disableScissor();

        renderScrollbar(g);
    }

    private void renderRow(GuiGraphics g, CircuitPrimitive p, int y, int mouseX, int mouseY) {
        boolean sel = selected.contains(p.id());
        boolean hover = mouseX >= listLeft && mouseX <= listRight
                && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);

        int top = y + 2, bottom = y + ROW_H - 2;
        g.fill(listLeft + 2, top, listRight - 2, bottom, sel ? SEL_ROW : ROW_BG);
        if (hover && !sel) g.fill(listLeft + 2, top, listRight - 2, bottom, ROW_HOVER);
        if (sel) {
            // bright border so the picked state is unmistakable
            g.fill(listLeft + 2, top, listRight - 2, top + 1, SEL_BORDER);
            g.fill(listLeft + 2, bottom - 1, listRight - 2, bottom, SEL_BORDER);
            g.fill(listLeft + 2, top, listLeft + 3, bottom, SEL_BORDER);
            g.fill(listRight - 3, top, listRight - 2, bottom, SEL_BORDER);
        }

        // Isometric visual of the build.
        int thumbX = listLeft + 6, thumbY = y + (ROW_H - THUMB) / 2;
        g.fill(thumbX, thumbY, thumbX + THUMB, thumbY + THUMB, THUMB_BG);
        try {
            renderIso(g, p.blocks(), thumbX + 2, thumbY + 2, THUMB - 4, THUMB - 4);
        } catch (Exception ignored) {
            // a bad block id never breaks the row
        }

        // Text block.
        int textX = thumbX + THUMB + 8;
        int textRight = listRight - 22;
        g.drawString(font, p.title(), textX, y + 6, TITLE_TEXT, false);
        String meta = p.footprint() + "   " + p.steps().size() + " steps   " + p.blocks().size() + " blocks";
        g.drawString(font, meta, textX, y + 18, META_TEXT, false);
        String desc = font.plainSubstrByWidth(p.description(), textRight - textX);
        g.drawString(font, desc, textX, y + 30, DESC_TEXT, false);

        // Checkbox on the right edge.
        int cb = listRight - 18, cy = y + (ROW_H - 12) / 2;
        g.fill(cb, cy, cb + 12, cy + 12, sel ? CHECK_GREEN : CHECK_EMPTY);
        g.fill(cb + 1, cy + 1, cb + 11, cy + 11, sel ? CHECK_GREEN : THUMB_BG);
        if (sel) {
            // little check tick
            g.fill(cb + 3, cy + 6, cb + 5, cy + 9, 0xFF0A2A0A);
            g.fill(cb + 5, cy + 4, cb + 9, cy + 9, 0xFF0A2A0A);
        }
    }

    /** Draws a small isometric voxel preview of {@code blocks} inside the given box using fills. */
    private void renderIso(GuiGraphics g, List<BlockEntry> blocks, int bx, int by, int bw, int bh) {
        if (blocks == null || blocks.isEmpty()) return;

        // Project each voxel: screen px = x - z, py = (x + z)/2 - y (world up = screen up).
        double minPx = Double.MAX_VALUE, maxPx = -Double.MAX_VALUE;
        double minPy = Double.MAX_VALUE, maxPy = -Double.MAX_VALUE;
        for (BlockEntry b : blocks) {
            double px = b.x() - b.z();
            double py = (b.x() + b.z()) / 2.0 - b.y();
            minPx = Math.min(minPx, px); maxPx = Math.max(maxPx, px);
            minPy = Math.min(minPy, py); maxPy = Math.max(maxPy, py);
        }
        double spanX = Math.max(maxPx - minPx, 0.001);
        double spanY = Math.max(maxPy - minPy, 0.001);
        double scale = Math.min((bw - 2) / (spanX + 1), (bh - 2) / (spanY + 1));
        scale = Mth.clamp(scale, 1.0, 7.0);
        int cell = (int) Math.ceil(scale) + 1;

        double contentW = spanX * scale, contentH = spanY * scale;
        double offX = bx + (bw - contentW) / 2.0 - minPx * scale;
        double offY = by + (bh - contentH) / 2.0 - minPy * scale;

        // Painter's order: far/low first so near/high overdraw.
        List<BlockEntry> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b) -> Integer.compare(a.x() + a.y() + a.z(), b.x() + b.y() + b.z()));

        for (BlockEntry b : sorted) {
            double px = b.x() - b.z();
            double py = (b.x() + b.z()) / 2.0 - b.y();
            int sx = (int) Math.round(offX + px * scale);
            int sy = (int) Math.round(offY + py * scale);
            int color = colorFor(b.block());
            g.fill(sx, sy, sx + cell, sy + cell, color);
            g.fill(sx, sy, sx + cell, sy + 1, lighten(color, 50));        // top highlight
            g.fill(sx, sy + cell - 1, sx + cell, sy + cell, lighten(color, -40)); // bottom shade
        }
    }

    /** Maps a block id to a representative colour so the iso preview reads at a glance. */
    private static int colorFor(String block) {
        String b = block == null ? "" : block.toLowerCase();
        if (b.contains("redstone_wire") || b.equals("minecraft:redstone")) return 0xFFE03434;
        if (b.contains("redstone_torch") || b.contains("redstone_wall_torch") || b.contains("torch")) return 0xFFFF5A3C;
        if (b.contains("repeater") || b.contains("comparator")) return 0xFFC07878;
        if (b.contains("redstone_lamp")) return 0xFFE7C766;
        if (b.contains("sticky_piston")) return 0xFF9FB04A;
        if (b.contains("piston")) return 0xFFB89A5C;
        if (b.contains("slime")) return 0xFF7BC043;
        if (b.contains("honey")) return 0xFFE0A832;
        if (b.contains("observer")) return 0xFF5E6068;
        if (b.contains("target")) return 0xFFE6CCA6;
        if (b.contains("lever") || b.contains("button")) return 0xFF8A8A8A;
        if (b.contains("dropper") || b.contains("dispenser") || b.contains("hopper")) return 0xFF50505A;
        if (b.contains("glass")) return 0xFFAFD3E2;
        if (b.contains("note_block") || b.contains("wool") || b.contains("planks") || b.contains("log")) return 0xFFA9794C;
        if (b.contains("stone") || b.contains("cobble") || b.contains("brick") || b.contains("deepslate")
                || b.contains("concrete") || b.contains("terracotta") || b.contains("smooth")) return 0xFF8C8C92;
        return 0xFF7A7A82;
    }

    private static int lighten(int argb, int delta) {
        int a = (argb >>> 24) & 0xFF;
        int r = Mth.clamp(((argb >> 16) & 0xFF) + delta, 0, 255);
        int gg = Mth.clamp(((argb >> 8) & 0xFF) + delta, 0, 255);
        int bb = Mth.clamp((argb & 0xFF) + delta, 0, 255);
        return (a << 24) | (r << 16) | (gg << 8) | bb;
    }

    private void renderScrollbar(GuiGraphics g) {
        int viewH = listBottom - listTop;
        int contentH = filtered.size() * ROW_H;
        if (contentH <= viewH) return;
        int barX = listRight - 3;
        int barH = Math.max(20, (int) ((long) viewH * viewH / contentH));
        int travel = viewH - barH;
        int maxScroll = contentH - viewH;
        int barY = listTop + (maxScroll == 0 ? 0 : (int) ((long) scrollOffset * travel / maxScroll));
        g.fill(barX, barY, barX + 2, barY + barH, SCROLL_BAR);
    }

    // --- input ---

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean superConsumed = super.mouseClicked(mx, my, button);
        LOGGER.info("[Rouge] Browser click: ({},{}) btn={} superConsumed={} list=[{}..{} x {}..{}] filtered={}",
                (int) mx, (int) my, button, superConsumed, listLeft, listRight, listTop, listBottom, filtered.size());
        if (superConsumed) return true;
        if (button == 0 && mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom
                && !filtered.isEmpty()) {
            int rel = (int) (my - listTop) + scrollOffset;
            int idx = rel / ROW_H;
            if (idx >= 0 && idx < filtered.size()) {
                toggle(filtered.get(idx).id());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= listLeft && mx <= listRight && my >= listTop && my <= listBottom) {
            scrollOffset -= (int) (delta * (ROW_H / 2.0));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, filtered.size() * ROW_H - (listBottom - listTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }
}
