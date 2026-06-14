package dev.dhanika.rouge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.dhanika.rouge.build.Difficulty;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.session.RougeSession.BuildMode;
import dev.dhanika.rouge.teach.LessonManager;
import dev.dhanika.rouge.teach.StepSession;
import dev.dhanika.rouge.ui.CircuitBrowserScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public final class RougeCommands {

    private RougeCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("rouge")
                        .executes(ctx -> {
                            RougeSession.toggle();
                            return 1;
                        })
                        .then(ClientCommandManager.literal("interact")
                                .executes(ctx -> {
                                    RougeSession.openInteractive();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("browse")
                                .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            if (!RougeSession.isOpen()) RougeSession.toggle();
                                            CircuitBrowserScreen.open(StringArgumentType.getString(ctx, "query"));
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    if (!RougeSession.isOpen()) RougeSession.toggle();
                                    CircuitBrowserScreen.open("");
                                    return 1;
                                }))
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
                        .then(ClientCommandManager.literal("btw")
                                .then(ClientCommandManager.argument("question", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            RougeSession.askBtw(StringArgumentType.getString(ctx, "question"));
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ChatDisplay.system("Usage: /rouge btw <question>  (shortcut: /btw <question>)");
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
                        // --- Build mode: hologram (default) or place (creative/SP only) ---
                        .then(ClientCommandManager.literal("mode")
                                .then(ClientCommandManager.literal("hologram")
                                        .executes(ctx -> {
                                            RougeSession.setBuildMode(BuildMode.HOLOGRAM);
                                            ChatDisplay.system("Build mode: hologram. Blocks will appear as ghosts for you to place manually.");
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("place")
                                        .executes(ctx -> {
                                            Player player = Minecraft.getInstance().player;
                                            if (!WorldPlacer.isAvailable()) {
                                                ChatDisplay.system("Place mode requires singleplayer.");
                                                return 0;
                                            }
                                            if (player == null || !player.isCreative()) {
                                                ChatDisplay.system("Place mode is only available in creative mode.");
                                                return 0;
                                            }
                                            RougeSession.setBuildMode(BuildMode.PLACE);
                                            ChatDisplay.system("Build mode: place. Blocks will be placed in-world as you confirm each step.");
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    String current = RougeSession.getBuildMode() == BuildMode.PLACE ? "place" : "hologram";
                                    ChatDisplay.system("Current build mode: " + current + ". Use /rouge mode hologram|place.");
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
