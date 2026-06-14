package dev.dhanika.rouge.inventory;

import dev.dhanika.rouge.build.BlockEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts inventory items required to place every block in a build. */
final class MaterialManifest {

    record Line(Item item, int count) {}

    private MaterialManifest() {}

    static List<Line> fromBlocks(List<BlockEntry> blocks, HolderLookup<Block> blockLookup) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (BlockEntry entry : blocks) {
            ItemStack stack = BlockItemResolver.resolve(entry.block(), blockLookup);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), 1, Integer::sum);
        }

        List<Line> lines = new ArrayList<>(counts.size());
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            lines.add(new Line(e.getKey(), e.getValue()));
        }
        lines.sort(Comparator.comparing(l -> l.item().getDescription().getString()));
        return lines;
    }

    static String summary(List<Line> lines, int maxEntries) {
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Line line : lines) {
            if (shown > 0) sb.append(", ");
            sb.append(line.item().getDescription().getString()).append(" x").append(line.count());
            if (++shown >= maxEntries) {
                if (lines.size() > maxEntries) sb.append(", …");
                break;
            }
        }
        return sb.toString();
    }
}
