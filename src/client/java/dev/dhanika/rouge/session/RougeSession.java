package dev.dhanika.rouge.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dhanika.rouge.ai.ChatMessage;
import dev.dhanika.rouge.ai.OpenRouterClient;
import dev.dhanika.rouge.ai.OpenRouterConfig;
import dev.dhanika.rouge.build.BlockEntry;
import dev.dhanika.rouge.build.BuildDirective;
import dev.dhanika.rouge.build.CircuitLibrary;
import dev.dhanika.rouge.build.SignalTracer;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.coach.BtwContext;
import dev.dhanika.rouge.prompt.SystemPrompts;
import dev.dhanika.rouge.render.RougeCatHud;
import dev.dhanika.rouge.teach.LessonManager;
import dev.dhanika.rouge.teach.StepSession;
import dev.dhanika.rouge.voice.RougeSpeech;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private enum Mode { CHAT, CONFIRM_BUILD, BUILDING }

    // Matches either fence name and captures the JSON body.
    private static final Pattern FENCE =
            Pattern.compile("```(?:rougebuild|stepplan)\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

    // Matches rougefix fences — a small list of blocks at absolute world coordinates, proposed
    // by the AI from a signal trace. Held until the player says "fix it".
    private static final Pattern FIX_FENCE =
            Pattern.compile("```rougefix\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

    // Phrases that mean "apply the pending fix now".
    private static final Pattern FIX_INTENT = Pattern.compile(
            "\\b(fix it|fix this|apply|apply the fix|apply fix|auto.?fix|just fix|please fix|"
            + "fix the (issue|problem|error|mistake|bug)|correct it|repair it|patch it)\\b",
            Pattern.CASE_INSENSITIVE);

    // Phrases that mean the player wants to debug their current build rather than ask a general
    // question. When matched, a live signal trace and (if a lesson is active) a solution-vs-world
    // comparison are injected into the next AI request so it can pinpoint the exact fault.
    private static final Pattern DEBUG_REQUEST = Pattern.compile(
            "\\b(why|what'?s wrong|what went wrong|doesn'?t work|don'?t work|not working|isn'?t working|"
            + "won'?t work|broken|debug|diagnose|trace|figure out|what'?s the issue|what is wrong|"
            + "not firing|not extending|not retracting|not powering|not lit|not lighting|"
            + "signal not|no signal|why is it|why isn'?t|why doesn'?t|why won'?t|why can'?t)\\b",
            Pattern.CASE_INSENSITIVE);

    // How many recent conversation messages (user+assistant) to resend each turn.
    // The system prompt and library context are always re-injected separately, so this
    // only bounds the running dialogue — keeping per-turn prefill fast as a session grows.
    private static final int MAX_HISTORY_MESSAGES = 16;

    // How many times, within one user turn, we ask the model to repair a malformed build
    // directive before falling back to asking the player to clarify.
    private static final int MAX_REPAIR_ATTEMPTS = 1;

    private static OpenRouterClient client;
    private static OpenRouterConfig config;
    private static boolean open = false;
    private static boolean awaitingReply = false;
    private static Mode mode = Mode.CHAT;
    private static StepPlan pendingPlan;
    private static int repairAttempts = 0;
    private static String lastQuery = "";
    // One-shot: the next dispatch is a /btw coaching turn (contextual Q&A, no build side effects).
    private static boolean coachingTurn = false;
    // Set while a coaching dispatch is in flight, so onReply treats the answer as pure Q&A.
    private static boolean awaitingCoachingReply = false;
    // One-shot: the next dispatch is a debug question — inject the solution-vs-world trace too.
    private static boolean pendingDebugTrace = false;
    // Blocks proposed by a rougefix fence, held until the player says "fix it". Coordinates are
    // absolute world coords, so WorldPlacer uses BlockPos.ZERO as the anchor.
    private static List<BlockEntry> pendingFix = null;
    private static String pendingFixDesc = null;
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
        repairAttempts = 0;
        lastQuery = "";
        coachingTurn = false;
        awaitingCoachingReply = false;
        pendingDebugTrace = false;
        pendingFix = null;
        pendingFixDesc = null;
        history.clear();
        history.add(ChatMessage.system(SystemPrompts.redstoneTutor()));
        client.prewarm(); // discover free models + warm the TLS connection before the first ask
        ChatDisplay.system("Session open. Ask me to teach you any redstone build — I'll show it step by step as a "
                + "translucent hologram you build against, and move on as you go. Set difficulty with "
                + "/rouge level easy|medium|hard. Type /rouge again to close.");
    }

    private static void close() {
        open = false;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        repairAttempts = 0;
        lastQuery = "";
        coachingTurn = false;
        awaitingCoachingReply = false;
        pendingDebugTrace = false;
        pendingFix = null;
        pendingFixDesc = null;
        history.clear();
        StepSession.reset();
        RougeSpeech.stop();
        ChatDisplay.system("Session closed. Chat is back to normal.");
    }

    public static void reset() {
        open = false;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        repairAttempts = 0;
        lastQuery = "";
        coachingTurn = false;
        awaitingCoachingReply = false;
        pendingDebugTrace = false;
        pendingFix = null;
        pendingFixDesc = null;
        history.clear();
        RougeSpeech.stop();
    }

    /**
     * Called by {@link StepSession} when a build ends on its own (auto-advance finished the last
     * step) or is stopped, so the session leaves BUILDING mode and treats the next message as
     * normal chat rather than a step command.
     */
    public static void endBuildMode() {
        if (open && mode == Mode.BUILDING) {
            mode = Mode.CHAT;
        }
    }

    /**
     * BTW coaching ({@code /btw} or {@code /rouge btw}): answers a redstone question grounded in
     * the current build, WITHOUT touching the hologram or proposing a build. The session keeps
     * whatever mode it was in (BUILDING stays BUILDING), so commands and the step-by-step build
     * continue normally right after the answer — exactly like asking a question mid-task.
     */
    public static void askBtw(String question) {
        if (question == null || question.isBlank()) {
            ChatDisplay.system("Usage: /btw <your question>  (or /rouge btw <question>)");
            return;
        }
        if (!open) {
            openSession();
        }
        // Keep the session in BUILDING mode while a hologram is live, so a follow-up "next"/"stop"
        // still routes through the build handler after we answer.
        if (StepSession.isActive()) {
            mode = Mode.BUILDING;
        }
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… one moment.");
            return;
        }
        String q = question.trim();
        ChatDisplay.userSaid(q); // echo the question, like Claude Code shows your /btw prompt
        sendToAi(q, true);
    }

    public static void handleUserMessage(String text) {
        if (!open || text == null || text.isBlank()) return;

        repairAttempts = 0; // a fresh user turn — allow the model a repair retry again

        // A "fix it" phrase applies a pending rougefix from any mode (debugging can happen in
        // plain chat, not just mid-build).
        if (pendingFix != null && looksLikeFixRequest(text)) {
            applyPendingFix();
            return;
        }

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
        if (isMoveRequest(text)) {
            StepSession.recenter(); // re-place the hologram without bothering the AI
            return;
        }
        // Check for fix intent before the YES/NO switch so "fix it" never accidentally advances
        // the step or stops the build.
        if (looksLikeFixRequest(text) || (pendingFix != null && Affirmation.of(text) == Affirmation.YES)) {
            applyPendingFix();
            return;
        }
        switch (Affirmation.of(text)) {
            case YES -> {
                StepSession.Advance result = StepSession.next();
                if (result != StepSession.Advance.MORE) {
                    mode = Mode.CHAT;
                }
            }
            case NO -> {
                if (pendingFix != null) {
                    // "no" while a fix is pending — discard it, keep the build running.
                    pendingFix = null;
                    pendingFixDesc = null;
                    ChatDisplay.system("Fix discarded — keep building.");
                    return;
                }
                StepSession.stop();
                mode = Mode.CHAT;
            }
            case OTHER -> sendToAi(text); // a real question mid-build — stay in BUILDING
        }
    }

    /** True when the message sounds like a debugging request about the current circuit's behaviour. */
    private static boolean looksLikeDebugRequest(String text) {
        return DEBUG_REQUEST.matcher(text).find();
    }

    /** True when the player is asking Rouge to apply a proposed fix. */
    private static boolean looksLikeFixRequest(String text) {
        return FIX_INTENT.matcher(text).find();
    }

    /** True for the short "bring the hologram to me" phrasings, handled locally (no AI call). */
    private static boolean isMoveRequest(String text) {
        String t = text.trim().toLowerCase().replaceAll("[!.?,]+$", "").trim();
        return t.equals("move") || t.equals("move it") || t.equals("here")
                || t.equals("move here") || t.equals("recenter") || t.equals("re-center")
                || t.equals("bring it here") || t.equals("come here");
    }

    private static void sendToAi(String text) {
        sendToAi(text, false);
    }

    private static void sendToAi(String text, boolean coaching) {
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… one moment.");
            return;
        }
        coachingTurn = coaching;
        if (looksLikeDebugRequest(text)) {
            pendingDebugTrace = true; // inject the solution-vs-world trace on this dispatch
        }
        lastQuery = text;
        history.add(ChatMessage.user(text));
        dispatchToAi();
    }

    /**
     * Builds the per-request context and fires the AI call. Shared by normal user turns and
     * by the build-directive repair retry, so both carry the same system prompt, build-library
     * context, and recent-dialogue window.
     */
    private static void dispatchToAi() {
        awaitingReply = true;
        RougeCatHud.startThinking(); // cat starts batting its yarn in the top-left HUD
        ChatDisplay.system("Rouge is thinking…");

        // A coaching (/btw) turn is a one-shot: capture and clear the flag so only this dispatch
        // is treated as contextual Q&A.
        boolean coaching = coachingTurn;
        coachingTurn = false;
        awaitingCoachingReply = coaching;

        // Per-request system context. For a /btw turn we swap the persona for the BTW coach prompt
        // and inject live build context; otherwise the normal persona + build library + step note.
        // Either way only a recent window of the dialogue is resent so prefill stays fast.
        List<ChatMessage> request = new ArrayList<>();
        if (coaching) {
            request.add(ChatMessage.system(SystemPrompts.btwCoach()));
            String ctx = BtwContext.forCurrentState();
            if (!ctx.isBlank()) {
                request.add(ChatMessage.system(ctx));
            }
        } else {
            if (!history.isEmpty()) {
                request.add(history.get(0)); // persona system prompt, always first
            }
            request.add(ChatMessage.system(CircuitLibrary.summary(lastQuery)));
            if (mode == Mode.BUILDING) {
                String ctx = StepSession.contextLine();
                if (!ctx.isBlank()) {
                    request.add(ChatMessage.system(
                            ctx + " Answer their question concisely; do NOT emit a new build unless they ask to change the design."));
                }
            }
        }

        // Signal tracer: inject a live CIRCUIT TRACE (exact coords, diagnosed break point, and a
        // simulated fix) so the AI never has to guess at the player's wiring. For explicit debug
        // questions, also inject the lesson context and the solution-vs-world comparison.
        String signalTrace = SignalTracer.traceSignalPath();
        if (!signalTrace.isBlank()) {
            request.add(ChatMessage.system(signalTrace));
        }
        if (pendingDebugTrace) {
            pendingDebugTrace = false;
            String lessonCtx = LessonManager.tutorContext();
            if (lessonCtx != null && !lessonCtx.isBlank()) {
                request.add(ChatMessage.system(lessonCtx));
            }
            String solutionTrace = SignalTracer.trace(LessonManager.solution(), LessonManager.anchor());
            if (!solutionTrace.isBlank()) {
                request.add(ChatMessage.system(solutionTrace));
            }
        }

        // Recent dialogue window (everything after the persona prompt at index 0).
        int from = Math.max(1, history.size() - MAX_HISTORY_MESSAGES);
        request.addAll(history.subList(from, history.size()));

        client.complete(request, status ->
                        Minecraft.getInstance().execute(() -> {
                            if (open) ChatDisplay.system(status);
                        }))
                .whenComplete((reply, err) ->
                        Minecraft.getInstance().execute(() -> onReply(reply, err)));
    }

    private static void onReply(String reply, Throwable err) {
        awaitingReply = false;
        RougeCatHud.stopThinking(); // reply landed — cat settles down
        if (!open) return;

        if (err != null) {
            awaitingCoachingReply = false;
            removeTrailingUserMessage();
            ChatDisplay.error(rootMessage(err));
            return;
        }

        // A /btw coaching answer is pure Q&A: strip any build/fix fence the model slipped in and
        // just print the prose. Mode is left untouched, so the hologram and step commands carry on.
        if (awaitingCoachingReply) {
            awaitingCoachingReply = false;
            String text = FIX_FENCE.matcher(FENCE.matcher(reply).replaceAll("")).replaceAll("").trim();
            if (text.isEmpty()) text = reply.trim();
            history.add(ChatMessage.assistant(reply));
            if (!text.isEmpty()) ChatDisplay.print(text);
            return;
        }

        // rougefix fence: a targeted block fix the AI derived from the signal trace. Held as
        // pendingFix until the player says "fix it"; the fence is stripped from the chat text.
        Matcher fm = FIX_FENCE.matcher(reply);
        if (fm.find()) {
            String fixJson = fm.group(1).trim();
            String chatPart = FIX_FENCE.matcher(reply).replaceAll("").trim();
            try {
                parseFix(fixJson);
                history.add(ChatMessage.assistant(chatPart.isEmpty() ? "[fix proposal]" : chatPart));
                if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
                int count = pendingFix != null ? pendingFix.size() : 0;
                ChatDisplay.system("Rouge has a " + count + "-block fix ready. Say \"fix it\" to apply"
                        + (WorldPlacer.isAvailable() ? "" : " (singleplayer only)") + ", or \"no\" to skip.");
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Failed to parse rougefix: {}", e.getMessage(), e);
                history.add(ChatMessage.assistant(reply));
                ChatDisplay.print(reply);
            }
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

                mode = Mode.BUILDING;
                StepSession.start(plan);
                if (!StepSession.isActive()) {
                    mode = Mode.CHAT;
                }

                history.add(ChatMessage.assistant(chatPart.isEmpty() ? "[build proposal]" : chatPart));
                if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Failed to parse build directive: {}\nRaw JSON:\n{}", e.getMessage(), planJson, e);
                attemptRepairOrClarify(reply, e.getMessage());
            }
            return;
        }

        history.add(ChatMessage.assistant(reply));
        ChatDisplay.print(reply);
    }

    /**
     * Parses a {@code rougefix} JSON body into {@link #pendingFix}. Coordinates in the fence are
     * absolute world coords; {@link WorldPlacer} uses {@link BlockPos#ZERO} as the anchor so the
     * blocks land exactly where the AI said.
     */
    private static void parseFix(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        pendingFixDesc = obj.has("desc") ? obj.get("desc").getAsString() : null;
        List<BlockEntry> blocks = new ArrayList<>();
        JsonArray arr = obj.getAsJsonArray("blocks");
        for (JsonElement el : arr) {
            JsonObject b = el.getAsJsonObject();
            blocks.add(new BlockEntry(b.get("x").getAsInt(), b.get("y").getAsInt(),
                    b.get("z").getAsInt(), b.get("block").getAsString()));
        }
        pendingFix = blocks;
    }

    /**
     * Applies the pending {@code rougefix}: normalizes each block to the logical fix for its
     * position (the solution's exact block where known, dust in place of any stray redstone_block),
     * rejects anything that would inject a new power source or swap a component, then places the
     * blocks in the singleplayer world.
     */
    private static void applyPendingFix() {
        if (pendingFix == null || pendingFix.isEmpty()) {
            ChatDisplay.system("No fix ready yet — ask me why something isn't working first, then say \"fix it\".");
            return;
        }
        if (!WorldPlacer.isAvailable()) {
            ChatDisplay.error("Auto-fix only works in singleplayer — place the blocks manually.");
            pendingFix = null;
            pendingFixDesc = null;
            return;
        }
        List<BlockEntry> fix = SignalTracer.normalizeFixToSolution(
                pendingFix, LessonManager.solution(), LessonManager.anchor());
        if (!SignalTracer.isSafeFixForSolution(fix, LessonManager.solution(), LessonManager.anchor())) {
            ChatDisplay.error("Fix cancelled — it would permanently alter the circuit (e.g. add a redstone "
                    + "block or replace a component). I'll explain the correct wiring instead.");
            pendingFix = null;
            pendingFixDesc = null;
            return;
        }
        int count = fix.size();
        // ZERO anchor: x/y/z in the fix are absolute world coordinates from the signal trace.
        WorldPlacer.placeStepBlocks(fix, BlockPos.ZERO);
        String desc = (pendingFixDesc != null && !pendingFixDesc.isBlank()) ? " — " + pendingFixDesc : "";
        ChatDisplay.system("Fixed: placed " + count + " block" + (count == 1 ? "" : "s") + desc + ".");
        pendingFix = null;
        pendingFixDesc = null;
    }

    /**
     * A build directive came back malformed. Instead of dead-ending on an error, we either
     * ask the model to repair its own output once (showing it the exact failure), or — if a
     * repair was already attempted this turn — ask the player to clarify. This keeps a single
     * bad generation from blocking the build.
     */
    private static void attemptRepairOrClarify(String badReply, String error) {
        if (repairAttempts >= MAX_REPAIR_ATTEMPTS) {
            ChatDisplay.print("I couldn't turn that into a buildable plan. Could you give me a little more to "
                    + "go on — the size, which way it should face, or a simpler version? "
                    + "(e.g. \"a 2x2 piston door facing north\")");
            return;
        }
        repairAttempts++;

        // Let the model see its own bad output plus the specific parse error, then re-emit a
        // clean directive. The corrective note is added to history so the retry has full context.
        history.add(ChatMessage.assistant(badReply));
        history.add(ChatMessage.user(
                "That build directive didn't parse (" + safeError(error) + "). Re-send ONLY a corrected "
                + "```rougebuild``` directive: use explicit \"steps\" with integer x/y/z and real 1.20.1 "
                + "block ids, never put a blueprint id in \"use\" or \"parts\", and put no text outside the "
                + "fence."));
        ChatDisplay.system("Let me fix that…");
        dispatchToAi();
    }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) return "invalid JSON";
        return error.length() > 160 ? error.substring(0, 160) + "…" : error;
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
