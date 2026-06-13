package dev.dhanika.rouge;

import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.ai.OpenRouterConfig;
import dev.dhanika.rouge.chat.ChatInterceptor;
import dev.dhanika.rouge.ai.ModelDiscovery;
import dev.dhanika.rouge.command.RougeCommands;
import dev.dhanika.rouge.render.GhostRenderer;
import dev.dhanika.rouge.session.RougeSession;
import dev.dhanika.rouge.teach.StepSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RougeClient implements ClientModInitializer {

    public static final String MOD_ID = "rouge";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        OpenRouterConfig config = new OpenRouterConfig();
        OpenRouterClient client = new OpenRouterClient(config);
        RougeSession.init(client, config);

        if (!config.hasToken()) {
            LOGGER.warn("[Rouge] No {} set — Rouge can open a session but can't reach the AI until you set it.",
                    OpenRouterConfig.TOKEN_ENV_VAR);
        }

        RougeCommands.register();
        ChatInterceptor.register();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GhostRenderer::render);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            RougeSession.reset();
            StepSession.reset();
            ModelDiscovery.invalidate();
        });

        LOGGER.info("Rouge initialized (model: {}).", config.model());
    }
}
