package dev.dhanika.rouge.inventory;

import dev.dhanika.rouge.build.BuildSpec;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/** Maps a block-state string from a build plan to the inventory item used to place it. */
final class BlockItemResolver {

    private static final Set<String> SKIP_BASE_IDS = Set.of(
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air",
            "minecraft:water",
            "minecraft:lava"
    );

    private BlockItemResolver() {}

    static ItemStack resolve(String blockState, HolderLookup<Block> blockLookup) {
        if (blockState == null || blockState.isBlank()) {
            return ItemStack.EMPTY;
        }

        String normalized = BuildSpec.normalizeBlockId(blockState);
        String baseId = BuildSpec.baseId(normalized);
        if (SKIP_BASE_IDS.contains(baseId)) {
            return ItemStack.EMPTY;
        }

        Item item = itemForBase(baseId);
        if (item == null || item == Items.AIR) {
            item = itemFromRegistry(normalized, blockLookup);
        }
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    private static Item itemForBase(String baseId) {
        return switch (baseId) {
            case "minecraft:redstone_wire", "minecraft:redstone" -> Items.REDSTONE;
            default -> null;
        };
    }

    private static Item itemFromRegistry(String blockState, HolderLookup<Block> blockLookup) {
        try {
            BlockStateParser.BlockResult parsed =
                    BlockStateParser.parseForBlock(blockLookup, blockState, false);
            return parsed.blockState().getBlock().asItem();
        } catch (Exception ignored) {
            return Items.AIR;
        }
    }
}
