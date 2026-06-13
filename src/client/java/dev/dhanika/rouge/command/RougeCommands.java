package dev.dhanika.rouge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.teach.LessonManager;
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
                        .then(ClientCommandManager.literal("move")
                                .executes(ctx -> {
                                    StepSession.recenter();
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
                                }))
                        // --- Lesson actions ---
                        .then(ClientCommandManager.literal("load")
                                .executes(ctx -> {
                                    LessonManager.loadSample();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("solution")
                                .executes(ctx -> {
                                    LessonManager.placeSolution();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("check")
                                .executes(ctx -> {
                                    LessonManager.check();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("level")
                                .then(ClientCommandManager.argument("level", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "level");
                                            LessonManager.setLevel(Difficulty.Level.of(name));
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ChatDisplay.system("Usage: /rouge level easy|medium|hard");
                                    return 1;
                                }))));
    }
}
