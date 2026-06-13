package dev.dhanika.rouge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.teach.StepSession;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class RougeCommands {

    private RougeCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("rouge")
                        .executes(ctx -> {
                            RougeSession.toggle();
                            return 1;
                        })
                        .then(ClientCommandManager.literal("next")
                                .executes(ctx -> {
                                    StepSession.next();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("step")
                                .executes(ctx -> {
                                    StepSession.showCurrent();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> {
                                    StepSession.stop();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("model")
                                .then(ClientCommandManager.argument("id", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            RougeSession.setModel(id);
                                            ChatDisplay.system("Model set to: " + id);
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ChatDisplay.system("Current model: " + RougeSession.getModel());
                                    return 1;
                                }))));
    }
}
