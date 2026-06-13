package dev.dhanika.rouge.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.dhanika.rouge.build.Difficulty.Level;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.teach.LessonManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Registers Rouge's client-side commands.
 * <p>
 * {@code /rouge} (no args) toggles the chat session. Subcommands drive the build
 * pipeline: {@code load} (sample lesson), {@code solution} (place the answer key).
 * New subcommands are one more {@code .then(...)} below — no other wiring needed.
 */
public final class RougeCommands {

    private RougeCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("rouge")
                        .executes(ctx -> {
                            RougeSession.toggle();
                            return 1;
                        })
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
                        .then(levelNode())));
    }

    /** {@code /rouge level <basic|easy|medium|hard>} — one child per difficulty. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> levelNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal("level");
        for (Level lvl : Level.values()) {
            node.then(ClientCommandManager.literal(lvl.lower())
                    .executes(ctx -> {
                        LessonManager.setLevel(lvl);
                        return 1;
                    }));
        }
        return node;
    }
}
