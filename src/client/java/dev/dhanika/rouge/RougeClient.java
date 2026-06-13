package dev.dhanika.rouge;

import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.ai.OpenRouterConfig;
import dev.dhanika.rouge.bridge.CanvasBridge;
import dev.dhanika.rouge.chat.ChatInterceptor;
import dev.dhanika.rouge.command.RougeCommands;
import dev.dhanika.rouge.compile.SketchCompiler;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.teach.ProactiveTutor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rouge entry point — the single wiring spot for the mod.
 * <p>
 * Builds the reusable AI client, hands it to the session, then registers the
 * {@code /rouge} command, the chat interceptor, and a disconnect reset. Future
 * features can be wired in here without touching the existing modules.
 */
public class RougeClient implements ClientModInitializer {

    public static final String MOD_ID = "rouge";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        OpenRouterConfig config = new OpenRouterConfig();
        OpenRouterClient client = new OpenRouterClient(config);
        RougeSession.init(client);

        if (!config.hasToken()) {
            LOGGER.warn("[Rouge] No {} set — Rouge can open a session but can't reach the AI until you set it.",
                    OpenRouterConfig.TOKEN_ENV_VAR);
        }

        RougeCommands.register();
        ChatInterceptor.register();
        ProactiveTutor.register();
        CanvasBridge.start(new SketchCompiler(client));

        // Never stay "open" across worlds/servers.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            RougeSession.reset();
            ProactiveTutor.reset();
        });

        LOGGER.info("Rouge initialized (model: {}).", config.model());
    }
}
