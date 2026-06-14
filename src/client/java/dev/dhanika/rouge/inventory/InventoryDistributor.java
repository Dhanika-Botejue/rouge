package dev.dhanika.rouge.inventory;

import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.teach.StepSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Gives the player the materials needed for the current build step (creative singleplayer). */
public final class InventoryDistributor {

    private InventoryDistributor() {}

    /**
     * {@code /rouge materials} — hands the player the items needed to place the current step's
     * new blocks. Stays completely silent if there is no active step.
     */
    public static void giveForCurrentStep() {
        if (!StepSession.isActive()) {
            return; // no current step — say nothing
        }
        List<BlockEntry> blocks = StepSession.activeStepBlocks();
        if (blocks.isEmpty()) {
            return; // step has no new blocks — nothing to hand out
        }
        giveCreative(blocks, StepSession.activeStepNumber(), StepSession.activeStepTotal());
    }

    private static void giveCreative(List<BlockEntry> blocks, int stepNum, int totalSteps) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        LocalPlayer clientPlayer = Minecraft.getInstance().player;
        if (server == null || clientPlayer == null) {
            ChatDisplay.system("Build materials work in singleplayer.");
            return;
        }
        if (!clientPlayer.isCreative()) {
            ChatDisplay.system("Build materials are creative-only for now. Switch to creative to receive them.");
            return;
        }

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(clientPlayer.getUUID());
            if (player == null) return;

            var blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            List<MaterialManifest.Line> lines = MaterialManifest.fromBlocks(blocks, blockLookup);
            if (lines.isEmpty()) {
                Minecraft.getInstance().execute(() ->
                        ChatDisplay.system("Couldn't resolve items for this step's blocks."));
                return;
            }

            boolean dropped = false;
            for (MaterialManifest.Line line : lines) {
                int remaining = line.count();
                int max = line.item().getMaxStackSize();
                while (remaining > 0) {
                    int chunk = Math.min(remaining, max);
                    ItemStack stack = new ItemStack(line.item(), chunk);
                    remaining -= chunk;
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                        dropped = true;
                    }
                }
            }

            final boolean overflow = dropped;
            String summary = MaterialManifest.summary(lines, 8);
            String prefix = "Step " + stepNum + "/" + totalSteps + " materials";
            Minecraft.getInstance().execute(() -> {
                if (overflow) {
                    ChatDisplay.system(prefix + " — " + summary
                            + ". Some stacks didn't fit and were dropped at your feet.");
                } else {
                    ChatDisplay.system(prefix + " — " + summary + ".");
                }
            });
        });
    }
}
