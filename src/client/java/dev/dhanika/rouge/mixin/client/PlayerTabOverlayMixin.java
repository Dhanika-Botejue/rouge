package dev.dhanika.rouge.mixin.client;

import dev.dhanika.rouge.ui.CommandPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tab is also Minecraft's player-list key, so opening Rouge's command panel would draw the
 * vanilla player-list box on top of it. While the panel is showing, suppress the player list
 * entirely — the player explicitly asked for the command reference, not the scoreboard.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rouge$suppressWhilePanelOpen(GuiGraphics guiGraphics, int width,
                                              Scoreboard scoreboard, Objective objective,
                                              CallbackInfo ci) {
        if (CommandPanel.isVisible()) {
            ci.cancel();
        }
    }
}
