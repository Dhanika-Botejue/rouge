package dev.dhanika.rouge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.BuildSpec;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws the current build step as a ghost preview floating in the world. Every block of the
 * build-so-far is rendered as its real block model but <b>semi-transparent</b>, so a ghost
 * block is instantly distinguishable from a solid block the player has actually placed. The
 * blocks added <i>this step</i> also get a bright green outline so the player knows exactly
 * what to place next; earlier blocks get a faint blue outline.
 *
 * <p>Self-contained: it renders through Fabric's {@link WorldRenderContext} and needs no
 * external mod. Transparency is automatic — block colours are forced to a fixed low alpha and
 * drawn on the translucent layer. Everything is guarded so a single bad block id or a renderer
 * hiccup never crashes the frame.
 */
public final class GhostRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    /** Alpha applied to every ghost block (0–255). Low enough to clearly read as "not placed yet". */
    private static final int GHOST_ALPHA = 110;

    // Outline colours (r, g, b, a).
    private static final float[] NEW_COLOR = {0.30f, 1.00f, 0.45f, 0.95f}; // bright green — place these now
    private static final float[] OLD_COLOR = {0.55f, 0.70f, 1.00f, 0.35f}; // faint blue — already shown

    private static volatile List<Ghost> ghosts = List.of();
    private static volatile boolean active = false;

    private GhostRenderer() {}

    /** A single block to render, already resolved to world coordinates and a block state. */
    private record Ghost(BlockPos pos, BlockState state, boolean isNew) {}

    /** Converts difficulty-filtered {@link BuildSpec.BlockEntry} list to render entries (drops role). */
    public static List<BlockEntry> fromSpec(List<BuildSpec.BlockEntry> blocks) {
        List<BlockEntry> out = new ArrayList<>(blocks.size());
        for (BuildSpec.BlockEntry b : blocks) {
            out.add(new BlockEntry(b.x(), b.y(), b.z(), b.block()));
        }
        return out;
    }

    /**
     * Shows the build at {@code anchor}. {@code all} is every block to render (in build-local
     * coordinates); {@code added} is just the blocks introduced this step, which get the bright
     * highlight.
     */
    public static void show(BlockPos anchor, List<BlockEntry> all, List<BlockEntry> added) {
        Set<Long> newKeys = new HashSet<>();
        for (BlockEntry b : added) {
            newKeys.add(BlockPos.asLong(b.x(), b.y(), b.z()));
        }

        List<Ghost> built = new ArrayList<>(all.size());
        for (BlockEntry b : all) {
            BlockState state = parse(b.block());
            if (state == null) continue;
            BlockPos world = anchor.offset(b.x(), b.y(), b.z());
            boolean isNew = newKeys.contains(BlockPos.asLong(b.x(), b.y(), b.z()));
            built.add(new Ghost(world, state, isNew));
        }
        ghosts = List.copyOf(built);
        active = true;
    }

    public static void clear() {
        active = false;
        ghosts = List.of();
    }

    /** Registered on {@code WorldRenderEvents.AFTER_TRANSLUCENT}. */
    public static void render(WorldRenderContext context) {
        if (!active) return;
        List<Ghost> snapshot = ghosts;
        if (snapshot.isEmpty()) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            PoseStack pose = context.matrixStack();
            Vec3 cam = context.camera().getPosition();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

            // 1. Real block models, drawn semi-transparent so the preview is obviously a ghost.
            //    We force every vertex's alpha and route the model onto the translucent layer.
            VertexConsumer translucent = new GhostAlpha(buffers.getBuffer(RenderType.translucent()), GHOST_ALPHA);
            MultiBufferSource ghostSource = renderType -> translucent;
            for (Ghost g : snapshot) {
                pose.pushPose();
                pose.translate(g.pos.getX() - cam.x, g.pos.getY() - cam.y, g.pos.getZ() - cam.z);
                dispatcher.renderSingleBlock(g.state, pose, ghostSource,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }

            // 2. Outlines: highlight this step's new blocks, dim the rest.
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (Ghost g : snapshot) {
                float[] c = g.isNew ? NEW_COLOR : OLD_COLOR;
                double x = g.pos.getX() - cam.x, y = g.pos.getY() - cam.y, z = g.pos.getZ() - cam.z;
                LevelRenderer.renderLineBox(pose, lines, x, y, z, x + 1, y + 1, z + 1,
                        c[0], c[1], c[2], c[3]);
            }

            buffers.endBatch();
        } catch (Exception e) {
            LOGGER.debug("[Rouge] Ghost render skipped this frame: {}", e.toString());
        }
    }

    private static BlockState parse(String blockId) {
        try {
            return BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), blockId, false)
                    .blockState();
        } catch (Exception e) {
            LOGGER.warn("[Rouge] Skipping unparseable block '{}': {}", blockId, e.getMessage());
            return null;
        }
    }

    /**
     * Wraps a block-layer {@link VertexConsumer} and forces a fixed alpha on every vertex
     * colour, turning an otherwise-opaque block model into a translucent ghost. All other
     * vertex attributes pass through unchanged.
     */
    private record GhostAlpha(VertexConsumer delegate, int alpha) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(r, g, b, alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
