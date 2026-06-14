package dev.dhanika.rouge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.session.RougeSession;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/** Standalone {@code /btw} shortcut for contextual redstone coaching (with or without an active build). */
public final class BtwCommands {

    private BtwCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("btw")
                        .then(ClientCommandManager.argument("question", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    RougeSession.askBtw(StringArgumentType.getString(ctx, "question"));
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ChatDisplay.system("Usage: /btw <your redstone question>");
                            return 1;
                        })));
    }
}
