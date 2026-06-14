package dev.dhanika.rouge.build;

import dev.dhanika.rouge.build.BuildSpec.BlockEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Places a build directly into the singleplayer world via the integrated server.
 * <p>
 * Block placement must run on the server thread, so we hop there with
 * {@link IntegratedServer#execute(Runnable)}. Works only in singleplayer (there is
 * no integrated server when connected to a remote server); callers should check
 * {@link #isAvailable()} and fall back to the overlay otherwise.
 */
public final class WorldPlacer {

    private WorldPlacer() {
    }

    /** True when a build can be placed (singleplayer with a running integrated server). */
    public static boolean isAvailable() {
        return Minecraft.getInstance().getSingleplayerServer() != null
                && Minecraft.getInstance().player != null;
    }

    /**
     * A default anchor: a few blocks in front of the player, at their feet. Use the
     * same anchor for the overlay/diff so everything lines up.
     */
    public static BlockPos defaultAnchor() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return BlockPos.ZERO;
        }
        return player.blockPosition().relative(player.getDirection(), 2);
    }

    /**
     * Places lighter {@link dev.dhanika.rouge.build.BlockEntry} blocks (position + block string,
     * no role field) into the world. Used by the signal-tracer auto-fix flow, where coordinates
     * are absolute world coords and the caller passes {@link BlockPos#ZERO} as the anchor.
     */
    public static boolean placeStepBlocks(List<dev.dhanika.rouge.build.BlockEntry> blocks, BlockPos anchor) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        LocalPlayer player = Minecraft.getInstance().player;
        if (server == null || player == null) {
            return false;
        }

        ResourceKey<Level> dimension = player.level().dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return;
            }
            HolderLookup<Block> blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (dev.dhanika.rouge.build.BlockEntry b : blocks) {
                try {
                    BlockState state = BlockStateParser.parseForBlock(blockLookup, b.block(), false).blockState();
                    level.setBlock(anchor.offset(b.x(), b.y(), b.z()), state, Block.UPDATE_ALL);
                } catch (Exception ignored) {
                    // Skip any block id/state the parser rejects.
                }
            }
        });
        return true;
    }

    /**
     * Places {@code blocks} with their local coords offset by {@code anchor}.
     * Returns false if no integrated server is available.
     */
    public static boolean place(List<BlockEntry> blocks, BlockPos anchor) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        LocalPlayer player = Minecraft.getInstance().player;
        if (server == null || player == null) {
            return false;
        }

        ResourceKey<Level> dimension = player.level().dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return;
            }
            HolderLookup<Block> blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (BlockEntry b : blocks) {
                try {
                    BlockState state = BlockStateParser.parseForBlock(blockLookup, b.block(), false).blockState();
                    level.setBlock(anchor.offset(b.x(), b.y(), b.z()), state, Block.UPDATE_ALL);
                } catch (Exception ignored) {
                    // Skip any block id/state the parser rejects.
                }
            }
        });
        return true;
    }
}
