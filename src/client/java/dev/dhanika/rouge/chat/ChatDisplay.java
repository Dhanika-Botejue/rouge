package dev.dhanika.rouge.chat;

import dev.dhanika.rouge.voice.RougeSpeech;
import dev.dhanika.rouge.voice.RougeSpeech.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Prints Rouge messages into the player's own chat HUD (client-side only —
 * nothing is sent to the server).
 * <p>
 * Full text always appears in chat; voice uses {@link Tier} so TTS stays short
 * and does not burn through ElevenLabs credits. All methods must be called on
 * the main client thread.
 */
public final class ChatDisplay {

    private static final String PREFIX = "[Rouge] ";

    private ChatDisplay() {
    }

    /** A normal Rouge reply — full text in chat, brief spoken intro only. */
    public static void print(String message) {
        emit(message, ChatFormatting.LIGHT_PURPLE, Tier.INTRO);
    }

    /** A build-step explanation — full text in chat, short spoken instruction. */
    public static void step(String message) {
        emit(message, ChatFormatting.LIGHT_PURPLE, Tier.STEP);
    }

    /** Spoken praise when a build step is cleared (2–4 words). */
    public static void praise(String message) {
        emit(message, ChatFormatting.LIGHT_PURPLE, Tier.PRAISE);
    }

    /** Echoes the player's own question (e.g. a /btw prompt), in aqua — chat only. */
    public static void userSaid(String message) {
        emit("[You] " + message, ChatFormatting.AQUA, Tier.OFF);
    }

    /** A system/status note (e.g. session opened, thinking…), in gray — chat only. */
    public static void system(String message) {
        emit(message, ChatFormatting.GRAY, Tier.OFF);
    }

    /** An error line, in red — short spoken summary. */
    public static void error(String message) {
        emit(message, ChatFormatting.RED, Tier.ERROR);
    }

    private static void emit(String message, ChatFormatting color, Tier speechTier) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null || message == null) {
            return;
        }
        boolean spoke = false;
        for (String line : message.split("\n", -1)) {
            client.gui.getChat().addMessage(
                    Component.literal(PREFIX + line).withStyle(color));
            // Multi-line messages: only voice the first non-blank line, and only once.
            if (!spoke && speechTier != Tier.OFF && !line.isBlank()) {
                RougeSpeech.speak(line, speechTier);
                spoke = true;
            }
        }
    }
}
