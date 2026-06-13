package dev.dhanika.rouge.session;

import dev.dhanika.rouge.ai.ChatMessage;
import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.ai.OpenRouterConfig;
import dev.dhanika.rouge.build.BuildDirective;
import dev.dhanika.rouge.build.CircuitLibrary;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.prompt.SystemPrompts;
import dev.dhanika.rouge.teach.StepSession;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the conversational state machine for a Rouge session.
 *
 * <ul>
 *   <li>{@code CHAT} — normal redstone Q&A. When the AI proposes a build (a
 *       {@code ```rougebuild} / {@code ```stepplan} fence), it is parsed and held as a
 *       pending plan, and Rouge asks the player to confirm.</li>
 *   <li>{@code CONFIRM_BUILD} — waiting on yes/no before starting the build. "Yes" starts
 *       the step-by-step hologram; "no" discards it; anything else is treated as a follow-up
 *       and routed back to the AI to refine the design.</li>
 *   <li>{@code BUILDING} — a step session is live. "next"/"yes" advances to the next step,
 *       "stop"/"no" ends it, and any real question is routed to the AI with build context so
 *       the player can get help mid-build.</li>
 * </ul>
 */
public final class RougeSession {

    private enum Mode { CHAT, CONFIRM_BUILD, BUILDING }

    // Matches either fence name and captures the JSON body.
    private static final Pattern FENCE =
            Pattern.compile("```(?:rougebuild|stepplan)\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

    private static OpenRouterClient client;
    private static OpenRouterConfig config;
    private static boolean open = false;
    private static boolean awaitingReply = false;
    private static Mode mode = Mode.CHAT;
    private static StepPlan pendingPlan;
    private static final List<ChatMessage> history = new ArrayList<>();

    private RougeSession() {}

    public static void init(OpenRouterClient openRouterClient, OpenRouterConfig openRouterConfig) {
        client = openRouterClient;
        config = openRouterConfig;
    }

    public static String getModel() { return config.model(); }

    public static void setModel(String id) { config.setModel(id); }

    public static boolean isOpen() { return open; }

    public static void toggle() {
        if (open) close();
        else openSession();
    }

    private static void openSession() {
        open = true;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        history.clear();
        history.add(ChatMessage.system(SystemPrompts.redstoneTutor()));
        ChatDisplay.system("Session open. Ask me to teach you any redstone build — I'll show it step by step in-world. Type /rouge again to close.");
    }

    private static void close() {
        open = false;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        history.clear();
        StepSession.reset();
        ChatDisplay.system("Session closed. Chat is back to normal.");
    }

    public static void reset() {
        open = false;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        history.clear();
    }

    public static void handleUserMessage(String text) {
        if (!open || text == null || text.isBlank()) return;

        switch (mode) {
            case CONFIRM_BUILD -> handleConfirm(text);
            case BUILDING -> handleBuilding(text);
            case CHAT -> sendToAi(text);
        }
    }

    // --- mode handlers ---

    private static void handleConfirm(String text) {
        switch (Affirmation.of(text)) {
            case YES -> {
                StepPlan plan = pendingPlan;
                pendingPlan = null;
                mode = Mode.BUILDING;
                StepSession.start(plan);
                if (!StepSession.isActive()) {
                    mode = Mode.CHAT; // start() bailed (empty plan)
                }
            }
            case NO -> {
                pendingPlan = null;
                mode = Mode.CHAT;
                ChatDisplay.system("No worries — ask me anything else, or describe a different build.");
            }
            case OTHER -> {
                // Treat as a follow-up: drop the stale proposal and let the AI re-plan.
                pendingPlan = null;
                mode = Mode.CHAT;
                sendToAi(text);
            }
        }
    }

    private static void handleBuilding(String text) {
        switch (Affirmation.of(text)) {
            case YES -> {
                StepSession.Advance result = StepSession.next();
                if (result != StepSession.Advance.MORE) {
                    mode = Mode.CHAT;
                }
            }
            case NO -> {
                StepSession.stop();
                mode = Mode.CHAT;
            }
            case OTHER -> sendToAi(text); // a real question mid-build — stay in BUILDING
        }
    }

    private static void sendToAi(String text) {
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… one moment.");
            return;
        }

        history.add(ChatMessage.user(text));
        awaitingReply = true;
        ChatDisplay.system("Rouge is thinking…");

        // Per-request system context: the build library, plus the current step if building.
        // Injected fresh each turn (not stored in history) to avoid bloating context.
        List<ChatMessage> request = new ArrayList<>(history);
        int insertAt = Math.min(1, request.size());
        request.add(insertAt, ChatMessage.system(CircuitLibrary.summary()));
        if (mode == Mode.BUILDING) {
            String ctx = StepSession.contextLine();
            if (!ctx.isBlank()) {
                request.add(insertAt + 1, ChatMessage.system(
                        ctx + " Answer their question concisely; do NOT emit a new build unless they ask to change the design."));
            }
        }

        client.complete(request).whenComplete((reply, err) ->
                Minecraft.getInstance().execute(() -> onReply(reply, err)));
    }

    private static void onReply(String reply, Throwable err) {
        awaitingReply = false;
        if (!open) return;

        if (err != null) {
            removeTrailingUserMessage();
            ChatDisplay.error(rootMessage(err));
            return;
        }

        Matcher m = FENCE.matcher(reply);
        boolean hasPlan = m.find();

        // Mid-build: ignore any build fence so we don't interrupt the active hologram.
        if (mode == Mode.BUILDING) {
            String text = hasPlan ? FENCE.matcher(reply).replaceAll("").trim() : reply;
            history.add(ChatMessage.assistant(reply));
            if (!text.isEmpty()) ChatDisplay.print(text);
            return;
        }

        if (hasPlan) {
            String planJson = m.group(1).trim();
            String chatPart = FENCE.matcher(reply).replaceAll("").trim();
            try {
                StepPlan plan = BuildDirective.resolve(planJson);
                if (plan.steps().isEmpty()) throw new IllegalStateException("empty plan");

                pendingPlan = plan;
                mode = Mode.CONFIRM_BUILD;
                history.add(ChatMessage.assistant(chatPart.isEmpty() ? "[build proposal]" : chatPart));
                if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
                ChatDisplay.system("Want me to walk you through building this in-world? Say \"yes\" to start, \"no\" to skip, or keep chatting to tweak it.");
            } catch (Exception e) {
                // Couldn't resolve the directive — fall back to showing the raw reply.
                history.add(ChatMessage.assistant(reply));
                ChatDisplay.print(reply);
            }
            return;
        }

        history.add(ChatMessage.assistant(reply));
        ChatDisplay.print(reply);
    }

    private static void removeTrailingUserMessage() {
        if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).role())) {
            history.remove(history.size() - 1);
        }
    }

    private static String rootMessage(Throwable err) {
        Throwable cause = (err instanceof CompletionException && err.getCause() != null)
                ? err.getCause() : err;
        String msg = cause.getMessage();
        return (msg == null || msg.isBlank()) ? cause.getClass().getSimpleName() : msg;
    }
}
