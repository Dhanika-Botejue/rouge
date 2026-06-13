package dev.dhanika.rouge.session;

import dev.dhanika.rouge.ai.ChatMessage;
import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.prompt.SystemPrompts;
import dev.dhanika.rouge.teach.LessonManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * The single source of truth for a Rouge conversation: whether it's open, the
 * running message history, and whether a request is in flight.
 * <p>
 * All state here is mutated only on the main client thread. The toggle and chat
 * hooks fire on the main thread, and the async OpenRouter reply is posted back
 * with {@link Minecraft#execute(Runnable)} before any state is touched.
 * <p>
 * History lives only while the session is open and is cleared on close (and on
 * disconnect), so each session starts fresh.
 */
public final class RougeSession {

    private static OpenRouterClient client;

    private static boolean open = false;
    private static boolean awaitingReply = false;
    private static final List<ChatMessage> history = new ArrayList<>();

    private RougeSession() {
    }

    /** Wires in the AI client. Call once from the mod initializer. */
    public static void init(OpenRouterClient openRouterClient) {
        client = openRouterClient;
    }

    public static boolean isOpen() {
        return open;
    }

    /** Opens the session if closed, closes it if open. */
    public static void toggle() {
        if (open) {
            close();
        } else {
            openSession();
        }
    }

    private static void openSession() {
        open = true;
        awaitingReply = false;
        history.clear();
        history.add(ChatMessage.system(SystemPrompts.redstoneTutor()));
        ChatDisplay.system("Session opened. Ask me anything about redstone. Type /rouge again to close.");
    }

    private static void close() {
        open = false;
        awaitingReply = false;
        history.clear();
        ChatDisplay.system("Session closed. Chat is back to normal.");
    }

    /** Full reset (e.g. on disconnect) — no chat output. */
    public static void reset() {
        open = false;
        awaitingReply = false;
        history.clear();
    }

    /**
     * Handles a chat message the player typed while the session is open: sends it
     * to OpenRouter and prints the reply. Must be called on the main thread.
     */
    public static void handleUserMessage(String text) {
        if (!open || text == null || text.isBlank()) {
            return;
        }
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… one moment.");
            return;
        }

        history.add(ChatMessage.user(text));
        awaitingReply = true;
        ChatDisplay.system("Rouge is thinking…");

        // Inject fresh lesson context (if a build is active) without storing it in
        // history, so Rouge tutors about the current build and the diff stays live.
        List<ChatMessage> request = new ArrayList<>(history);
        String lessonContext = LessonManager.tutorContext();
        if (lessonContext != null) {
            request.add(Math.min(1, request.size()), ChatMessage.system(lessonContext));
        }

        client.complete(request).whenComplete((reply, err) ->
                Minecraft.getInstance().execute(() -> onReply(reply, err)));
    }

    /** Runs on the main thread once the request finishes (success or failure). */
    private static void onReply(String reply, Throwable err) {
        awaitingReply = false;

        // The session may have been closed while the request was in flight.
        if (!open) {
            return;
        }

        if (err != null) {
            // Drop the unanswered user turn so history doesn't end on two user
            // messages, letting the player simply retry.
            removeTrailingUserMessage();
            ChatDisplay.error(rootMessage(err));
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

    /** Unwraps CompletionException to surface the underlying readable message. */
    private static String rootMessage(Throwable err) {
        Throwable cause = (err instanceof CompletionException && err.getCause() != null)
                ? err.getCause() : err;
        String msg = cause.getMessage();
        return (msg == null || msg.isBlank()) ? cause.getClass().getSimpleName() : msg;
    }
}
