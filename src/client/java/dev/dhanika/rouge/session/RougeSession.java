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
import dev.dhanika.rouge.build.CircuitPrimitive;
import dev.dhanika.rouge.build.PlanningOutline;
import dev.dhanika.rouge.build.SignalTracer;
import dev.dhanika.rouge.build.StepPlan;
import dev.dhanika.rouge.build.WorldPlacer;
import dev.dhanika.rouge.chat.ChatDisplay;
import dev.dhanika.rouge.prompt.SystemPrompts;
import dev.dhanika.rouge.render.GhostRenderer;
import dev.dhanika.rouge.render.ThinkingHud;
import dev.dhanika.rouge.teach.LessonManager;
import dev.dhanika.rouge.teach.StepSession;
import dev.dhanika.rouge.ui.CircuitBrowserScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Owns the conversational state machine for a Rouge session.
 *
 * <ul>
 *   <li>{@code CHAT} — normal redstone Q&A. When the AI proposes a build (a
 *       {@code ```rougebuild} / {@code ```stepplan} fence), it is parsed and held as a
 *       pending plan, and Rouge asks the player to confirm.</li>
 *   <li>{@code PLANNING} — collaborative design phase. The AI has emitted a
 *       {@code ```rougeplanning} outline; the player can refine it in conversation. Saying
 *       "build it" sends the AI a commit prompt; saying "cancel" returns to CHAT.</li>
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

    private enum Mode { CHAT, PLANNING, CONFIRM_BUILD, BUILDING }

    // Matches rougebuild / stepplan fences and captures the JSON body.
    private static final Pattern FENCE =
            Pattern.compile("```(?:rougebuild|stepplan)\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

    // Matches rougefix fences — a small list of blocks at absolute world coordinates.
    private static final Pattern FIX_FENCE =
            Pattern.compile("```rougefix\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

    // Phrases that mean "apply the pending fix now".
    private static final Pattern FIX_INTENT = Pattern.compile(
            "\\b(fix it|fix this|apply|apply the fix|apply fix|auto.?fix|just fix|please fix|"
            + "fix the (issue|problem|error|mistake|bug)|correct it|repair it|patch it)\\b",
            Pattern.CASE_INSENSITIVE);

    // Matches rougeplanning fences and captures the JSON body.
    private static final Pattern PLANNING_FENCE =
            Pattern.compile("```rougeplanning\\s*\\n([\\s\\S]*?)\\n?```", Pattern.DOTALL);

        // Matches <think>...</think> reasoning blocks emitted by some models (e.g. DeepSeek R1).
    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // How many recent conversation messages (user+assistant) to resend each turn.
    // The system prompt and library context are always re-injected separately, so this
    // only bounds the running dialogue — keeping per-turn prefill fast as a session grows.
    private static final int MAX_HISTORY_MESSAGES = 16;

    // How many times, within one user turn, we ask the model to repair a malformed build
    // directive before falling back to asking the player to clarify.
    private static final int MAX_REPAIR_ATTEMPTS = 1;

    /** Build output mode: HOLOGRAM shows ghost blocks; PLACE actually places them (creative/SP only). */
    public enum BuildMode { HOLOGRAM, PLACE }

    private static OpenRouterClient client;
    private static OpenRouterConfig config;
    private static boolean open = false;
    private static boolean awaitingReply = false;
    private static Mode mode = Mode.CHAT;
    private static BuildMode buildMode = BuildMode.HOLOGRAM;
    private static StepPlan pendingPlan;
    private static PlanningOutline pendingOutline;
    private static int repairAttempts = 0;
    // True while we await the AI's reply to a build-browser stitch request, so the resulting
    // build is routed through a preview + confirm (CONFIRM_BUILD) instead of building at once.
    private static boolean awaitingStitch = false;
    // True when the next build request should go to the AI planning flow (set by /rouge interact),
    // bypassing the build-browser intercept for that one message.
    private static boolean primedInteractive = false;
    private static String lastQuery = "";

    // Build-intent verbs: a chat message matching these (in CHAT mode) opens the build browser
    // so the player can pick parts, instead of going straight to the AI. Conceptual questions
    // (no build verb) still flow to the AI as normal redstone Q&A.
    private static final Pattern BUILD_VERBS = Pattern.compile(
            "\\b(build|rebuild|make|create|construct|assemble|wire)\\b", Pattern.CASE_INSENSITIVE);

    // Phrases that indicate the player wants to debug their current build rather than ask a
    // general redstone question. When matched with an active lesson, a live signal trace is
    // injected into the AI request so it can pinpoint the exact issue.
    private static final Pattern DEBUG_REQUEST = Pattern.compile(
            "\\b(why|what'?s wrong|what went wrong|doesn'?t work|don'?t work|not working|isn'?t working|"
            + "won'?t work|broken|debug|diagnose|trace|fix it|figure out|what'?s the issue|what is wrong|"
            + "not firing|not extending|not retracting|not powering|not lit|not lighting|"
            + "signal not|no signal|why is it|why isn'?t|why doesn'?t|why won'?t|why can'?t)\\b",
            Pattern.CASE_INSENSITIVE);

    // Set to true for one AI dispatch when the player asks a debug question with an active
    // lesson — causes the signal trace to be injected into the request.
    private static boolean pendingDebugTrace = false;

    // Blocks proposed by a rougefix fence, held until the player says "fix it".
    // Coordinates are absolute world coords so WorldPlacer uses BlockPos.ZERO as anchor.
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

    public static BuildMode getBuildMode() { return buildMode; }

    public static void setBuildMode(BuildMode m) { buildMode = m; }

    public static boolean isOpen() { return open; }

    public static void toggle() {
        if (open) close();
        else openSession();
    }

    /**
     * Opens a session (if not already open) and primes it for interactive planning mode.
     * The next message from the player will trigger a rougeplanning outline instead of an
     * immediate build directive.
     */
    public static void openInteractive() {
        if (!open) openSession();
        // Inject a one-shot system note into history so the AI knows to use planning mode
        // on the very next user message, without spending an API call upfront.
        history.add(ChatMessage.system(
                "The player has explicitly requested interactive planning mode. For their next build request, "
                + "respond with a rougeplanning outline fence — do NOT jump straight to a rougebuild."));
        // The next build request should plan with the AI, not open the part picker.
        primedInteractive = true;
        ChatDisplay.system("Interactive mode on. Describe the build you want — we'll plan it together before building.");
    }

    /**
     * Stitches a set of hand-picked library circuits into one build. Invoked from the build
     * browser window: the player ranks/searches the library, checks the parts they want, and
     * Rouge merges the most relevant section of each into a single design matching {@code goal}.
     *
     * <p>The picked designs' verified block data is handed to the AI with a merge instruction;
     * the returned build is routed through {@link Mode#CONFIRM_BUILD} so the player sees a gold
     * preview hologram and confirms before the step-by-step build begins.
     */
    public static void stitchSelected(String goal, List<CircuitPrimitive> parts) {
        if (parts == null || parts.isEmpty()) return;
        if (!open) openSession();
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… give it a moment, then stitch again.");
            return;
        }

        // Drop any half-finished interaction; a fresh stitch supersedes it.
        mode = Mode.CHAT;
        pendingPlan = null;
        pendingOutline = null;
        repairAttempts = 0;
        GhostRenderer.clearPreview();
        awaitingStitch = true;

        history.add(ChatMessage.system(stitchContext(goal, parts)));
        String userMsg = (goal == null || goal.isBlank())
                ? "Stitch the circuits I selected into one combined build."
                : "Stitch the circuits I selected into: " + goal.trim();
        lastQuery = (goal == null) ? "" : goal;
        history.add(ChatMessage.user(userMsg));

        String names = parts.stream().map(CircuitPrimitive::title).collect(Collectors.joining(", "));
        ChatDisplay.system("Stitching " + parts.size() + " design" + (parts.size() == 1 ? "" : "s")
                + " together (" + names + ")…");
        dispatchToAi();
    }

    /** Per-call system context for a stitch: the merge instruction plus each part's block data. */
    private static String stitchContext(String goal, List<CircuitPrimitive> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("STITCH REQUEST. The player opened the build browser and hand-picked the verified library ")
          .append("circuits below. Take the MOST RELEVANT section of EACH selected circuit and merge those ")
          .append("sections into ONE coherent, working redstone build that best matches the player's goal");
        sb.append((goal == null || goal.isBlank()) ? ". " : ": \"" + goal.trim() + "\". ");
        sb.append("Do NOT simply place them side by side — keep the parts that matter, drop what doesn't, ")
          .append("reposition blocks so nothing overlaps, and add the minimal redstone needed to connect them.\n\n")
          .append("SELECTED CIRCUITS (verified block data; each circuit's coordinates are in its own local space):\n");
        for (CircuitPrimitive p : parts) {
            sb.append("=== ").append(p.title()).append(" [id=").append(p.id())
              .append(", ").append(p.footprint()).append("] — ").append(p.description()).append('\n');
            sb.append("blocks: ").append(blocksJson(p.blocks())).append('\n');
        }
        sb.append("\nReply with exactly ONE ```rougebuild``` CUSTOM directive: a \"circuit\" name and \"steps\" whose ")
          .append("\"blocks\" are CUMULATIVE (each step lists every block placed so far), reusing the real block ids ")
          .append("and coordinates above where they fit. Aim for 3-7 steps, each a meaningful chunk with a short ")
          .append("teaching \"explanation\". Then one short plain-text line introducing the stitched build, nothing else.");
        return sb.toString();
    }

    /** Compact JSON array of block entries for embedding in the stitch context. */
    private static String blocksJson(List<BlockEntry> blocks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            BlockEntry b = blocks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"x\":").append(b.x()).append(",\"y\":").append(b.y())
              .append(",\"z\":").append(b.z()).append(",\"block\":\"").append(b.block()).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static void openSession() {
        open = true;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        pendingOutline = null;
        repairAttempts = 0;
        awaitingStitch = false;
        primedInteractive = false;
        lastQuery = "";
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
        pendingOutline = null;
        GhostRenderer.clearPreview();
        repairAttempts = 0;
        awaitingStitch = false;
        primedInteractive = false;
        pendingDebugTrace = false;
        pendingFix = null;
        pendingFixDesc = null;
        lastQuery = "";
        history.clear();
        StepSession.reset();
        ChatDisplay.system("Session closed. Chat is back to normal.");
    }

    public static void reset() {
        open = false;
        awaitingReply = false;
        mode = Mode.CHAT;
        pendingPlan = null;
        pendingOutline = null;
        repairAttempts = 0;
        awaitingStitch = false;
        primedInteractive = false;
        pendingDebugTrace = false;
        pendingFix = null;
        pendingFixDesc = null;
        lastQuery = "";
        history.clear();
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

    public static void handleUserMessage(String text) {
        if (!open || text == null || text.isBlank()) return;

        ChatDisplay.userSaid(text); // echo player's own message so it's visible in chat
        repairAttempts = 0; // a fresh user turn — allow the model a repair retry again

        switch (mode) {
            case CONFIRM_BUILD -> handleConfirm(text);
            case BUILDING -> handleBuilding(text);
            case PLANNING -> handlePlanning(text);
            case CHAT -> handleChat(text);
        }
    }

    /**
     * Routes a chat-mode message. A build request opens the part picker (build browser) seeded
     * with the request, so the player always gets to select parts first. Conceptual questions go
     * to the AI as normal redstone Q&A. An explicit {@code /rouge interact} priming takes
     * precedence for one message so the AI planning flow still works.
     */
    private static void handleChat(String text) {
        // A pending fix proposed by the AI waits here for explicit confirmation.
        if (pendingFix != null) {
            Affirmation aff = Affirmation.of(text);
            if (looksLikeFixRequest(text) || aff == Affirmation.YES) {
                applyPendingFix();
                return;
            }
            if (aff == Affirmation.NO) {
                pendingFix = null;
                pendingFixDesc = null;
                ChatDisplay.system("Fix discarded. Ask me anything else.");
                return;
            }
            // Any real question clears the stale fix and falls through to the AI.
            pendingFix = null;
            pendingFixDesc = null;
        }

        if (primedInteractive) {
            primedInteractive = false;
            sendToAi(text);
            return;
        }
        if (looksLikeBuildRequest(text)) {
            CircuitBrowserScreen.open(extractTopic(text));
            return;
        }
        // Outside a build: only pull in the world scan when the player is explicitly
        // asking about a problem (not every casual redstone question).
        if (looksLikeDebugRequest(text)) {
            pendingDebugTrace = true;
        }
        sendToAi(text);
    }

    /** True when a chat message reads as a request to BUILD something (vs. a question about redstone). */
    private static boolean looksLikeBuildRequest(String text) {
        String t = text.toLowerCase().strip();
        if (t.isEmpty()) return false;

        // Pure questions / explanations → always go to the AI.
        if (t.startsWith("what ") || t.startsWith("why ") || t.startsWith("does ")
                || t.startsWith("is ") || t.startsWith("are ") || t.startsWith("can you explain")
                || t.startsWith("explain") || t.startsWith("tell me about")
                || t.startsWith("how does") || t.startsWith("how do redstone")
                || t.startsWith("how do they") || t.startsWith("how do these")) {
            return false;
        }

        // "build/make/create/wire me a …" or bare build verb at the start → browser.
        if (BUILD_VERBS.matcher(t).find()) {
            // Exclude "what makes …", "why make …", "how does … make …" etc.
            // A build verb is only a trigger if it appears as the main intent, not inside a question.
            if (t.startsWith("what ") || t.startsWith("why ") || t.startsWith("how does")
                    || t.startsWith("how do") && !t.startsWith("how do i " )
                    && !t.startsWith("how do you ")) {
                return false;
            }
            return true;
        }

        // "how do I build/make/wire …" or "how to build …" — only when followed by a build verb.
        if ((t.startsWith("how do i ") || t.startsWith("how do you ") || t.startsWith("how to "))) {
            return BUILD_VERBS.matcher(t).find();
        }

        // "show me how to build/make …" — only when a build verb follows.
        if (t.startsWith("show me how")) {
            return BUILD_VERBS.matcher(t).find();
        }

        // "teach me to build/make …" — only with a build verb.
        if (t.startsWith("teach me") || t.startsWith("teach ")) {
            return BUILD_VERBS.matcher(t).find();
        }

        // "I want/need to build/make …"
        if (t.matches("^i (want|need|wanna|would like|'d like)\\b.*")) {
            return BUILD_VERBS.matcher(t).find();
        }

        return false;
    }

    /**
     * Reduces a build request to clean search keywords by stripping leading filler/verb words,
     * so the picker's search box seeds with the topic ("flying machine") not the whole sentence
     * ("build me a flying machine").
     */
    private static String extractTopic(String text) {
        String t = text.strip().replaceAll("[?.!]+$", "").strip();
        String[] tokens = t.split("\\s+");
        java.util.Set<String> filler = java.util.Set.of(
                "build", "rebuild", "make", "create", "construct", "assemble", "wire", "design",
                "teach", "show", "how", "do", "to", "i", "want", "need", "wanna", "would", "like",
                "give", "can", "could", "you", "please", "lets", "let's", "me", "us", "a", "an",
                "the", "some", "my", "another", "new", "up");
        int i = 0;
        while (i < tokens.length && filler.contains(tokens[i].toLowerCase())) i++;
        String topic = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length)).strip();
        return topic.isEmpty() ? t : topic;
    }

    /** Player picked one design in the build picker. */
    public static void buildSelected(CircuitPrimitive circuit, String goal) {
        if (circuit == null) return;
        if (!open) openSession();

        if (circuit.isBuildable()) {
            pendingPlan = null;
            mode = Mode.BUILDING;
            StepSession.start(circuit.toStepPlan());
            if (!StepSession.isActive()) {
                mode = Mode.CHAT;
            } else {
                ChatDisplay.system("Building " + circuit.title() + " from the library.");
            }
            return;
        }

        String prompt = (goal == null || goal.isBlank()) ? circuit.title() : goal.strip();
        ChatDisplay.system("Blueprint selected — Rouge will generate " + circuit.title() + "…");
        buildWithAi(prompt + " (use the " + circuit.id() + " blueprint from the library)");
    }

    /**
     * Defers to the AI to pick (and stitch) a build itself — the behavior behind the build
     * browser's "Let Rouge choose" button. Sends the goal straight to the AI, bypassing the
     * picker intercept so it doesn't loop back into the window.
     */
    public static void buildWithAi(String goal) {
        if (!open) openSession();
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… give it a moment.");
            return;
        }
        mode = Mode.CHAT;
        pendingPlan = null;
        pendingOutline = null;
        awaitingStitch = false;
        primedInteractive = false;
        GhostRenderer.clearPreview();
        String msg = (goal == null || goal.isBlank())
                ? "Pick a redstone build that fits and show me." : goal.strip();
        ChatDisplay.system("Letting Rouge pick a build for you…");
        sendToAi(msg);
    }

    // --- mode handlers ---

    private static void handlePlanning(String text) {
        switch (Affirmation.of(text)) {
            case YES -> {
                // Player approved the outline — ask AI to commit it as a build directive.
                // Keep the preview hologram visible until the real build hologram takes over.
                String circuit = pendingOutline != null ? pendingOutline.circuit() : "the design we discussed";
                pendingOutline = null;
                mode = Mode.CHAT;
                sendToAi("Looks good — go ahead and generate the rougebuild directive for " + circuit + ".");
            }
            case NO -> {
                pendingOutline = null;
                GhostRenderer.clearPreview();
                mode = Mode.CHAT;
                ChatDisplay.system("Planning cancelled. Ask me anything else, or describe a different build.");
            }
            case OTHER -> {
                // Treat as a refinement — AI will re-emit an updated rougeplanning fence.
                sendToAi(text);
            }
        }
    }

    private static void handleConfirm(String text) {
        switch (Affirmation.of(text)) {
            case YES -> {
                StepPlan plan = pendingPlan;
                pendingPlan = null;
                GhostRenderer.clearPreview(); // hand off from the gold preview to the step hologram
                mode = Mode.BUILDING;
                StepSession.start(plan, buildMode == BuildMode.PLACE);
                if (!StepSession.isActive()) {
                    mode = Mode.CHAT; // start() bailed (empty plan)
                }
            }
            case NO -> {
                pendingPlan = null;
                GhostRenderer.clearPreview();
                mode = Mode.CHAT;
                ChatDisplay.system("No worries — discarded that build. Ask me anything else, or browse parts to stitch again.");
            }
            case OTHER -> {
                // Treat as a follow-up: drop the stale proposal and let the AI re-plan.
                pendingPlan = null;
                GhostRenderer.clearPreview();
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
        // Check for fix intent before the YES/NO affirmation switch so "fix it" never
        // accidentally advances the step or stops the build.
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
                    // "no" when a fix is pending — discard the fix, keep the build running.
                    pendingFix = null;
                    pendingFixDesc = null;
                    ChatDisplay.system("Fix discarded. Keep building — I'll auto-advance when each step is done.");
                    return;
                }
                StepSession.stop();
                mode = Mode.CHAT;
            }
            case OTHER -> sendToAi(text); // stay in BUILDING; world scan is always injected
        }
    }

    /** True when the message sounds like a debugging request about the current circuit's behavior. */
    private static boolean looksLikeDebugRequest(String text) {
        return DEBUG_REQUEST.matcher(text).find();
    }

    /** True when the player is asking Rouge to apply a proposed fix. */
    private static boolean looksLikeFixRequest(String text) {
        return FIX_INTENT.matcher(text).find();
    }

    /**
     * Parses a {@code rougefix} JSON body and stores the result in {@link #pendingFix}.
     * Coordinates in the fence are absolute world coords; {@link WorldPlacer} uses
     * {@link BlockPos#ZERO} as the anchor so they are placed exactly where the AI said.
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
     * Applies the pending fix from the last {@code rougefix} fence, or falls back to
     * completing the active build when no specific fix is pending.
     */
    private static void applyPendingFix() {
        if (pendingFix != null && !pendingFix.isEmpty()) {
            if (!WorldPlacer.isAvailable()) {
                ChatDisplay.error("Auto-fix only works in singleplayer — place the blocks manually.");
                pendingFix = null;
                pendingFixDesc = null;
                return;
            }
            // Rewrite each fix block to the logical block for its position: the solution's exact
            // block where one is known, and dust in place of any stray redstone_block. This is
            // what corrects a model that proposed a redstone_block where redstone dust belongs.
            List<BlockEntry> fix = SignalTracer.normalizeFixToSolution(
                    pendingFix, LessonManager.solution(), LessonManager.anchor());
            // Safety: reject fixes that introduce permanent power sources or blocks outside the design.
            if (!SignalTracer.isSafeFixForSolution(fix, LessonManager.solution(), LessonManager.anchor())) {
                ChatDisplay.error("Fix cancelled — it would permanently alter the circuit (e.g. add a redstone block or replace a component). I'll explain the correct wiring instead.");
                pendingFix = null;
                pendingFixDesc = null;
                return;
            }
            int count = fix.size();
            // ZERO anchor: x/y/z in the fix are absolute world coordinates from the signal trace.
            WorldPlacer.placeStepBlocks(fix, BlockPos.ZERO);
            String desc = (pendingFixDesc != null && !pendingFixDesc.isBlank())
                    ? " — " + pendingFixDesc : "";
            ChatDisplay.system("Fixed: placed " + count + " block" + (count == 1 ? "" : "s") + desc + ".");
            pendingFix = null;
            pendingFixDesc = null;
            return;
        }
        // No specific fix pending — complete the active build if one is running.
        if (mode == Mode.BUILDING && LessonManager.solution() != null) {
            if (!WorldPlacer.isAvailable()) {
                ChatDisplay.error("Auto-fix only works in singleplayer.");
                return;
            }
            LessonManager.placeSolution();
        } else {
            ChatDisplay.system("No fix ready yet — ask me why something isn't working first, then say \"fix it\".");
        }
    }

    /** True for the short "bring the hologram to me" phrasings, handled locally (no AI call). */
    private static boolean isMoveRequest(String text) {
        String t = text.trim().toLowerCase().replaceAll("[!.?,]+$", "").trim();
        return t.equals("move") || t.equals("move it") || t.equals("here")
                || t.equals("move here") || t.equals("recenter") || t.equals("re-center")
                || t.equals("bring it here") || t.equals("come here");
    }

    private static void sendToAi(String text) {
        if (awaitingReply) {
            ChatDisplay.system("Rouge is still thinking… one moment.");
            return;
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
        ThinkingHud.start();
        ChatDisplay.system("Rouge is thinking…");

        // Per-request system context: the persona prompt, the build library, and the current
        // step if building. The library context is injected fresh each turn (not stored in
        // history) to avoid bloating it. Only a recent window of the dialogue is resent so
        // per-turn prefill stays fast as a long teaching session grows.
        List<ChatMessage> request = new ArrayList<>();
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
        // Always inject the signal path trace so the AI has exact coordinates, a diagnosed
        // break point, and a simulated fix result — regardless of mode or phrasing.
        String signalTrace = SignalTracer.traceSignalPath();
        if (!signalTrace.isBlank()) {
            request.add(ChatMessage.system(signalTrace));
        }
        // For explicit debug questions, also inject the solution comparison trace.
        if (pendingDebugTrace) {
            pendingDebugTrace = false;
            String lessonCtx = LessonManager.tutorContext(lastQuery);
            if (lessonCtx != null) {
                request.add(ChatMessage.system(lessonCtx));
                String solutionTrace = SignalTracer.trace(LessonManager.solution(), LessonManager.anchor());
                if (!solutionTrace.isBlank()) request.add(ChatMessage.system(solutionTrace));
            }
        }
        if (mode == Mode.PLANNING && pendingOutline != null) {
            request.add(ChatMessage.system(
                    "ACTIVE PLANNING: " + pendingOutline.circuit() + ". The player is reviewing and refining the "
                    + "build plan. Re-emit a rougeplanning fence with any updates, OR emit a rougebuild fence "
                    + "only if the player explicitly asks to build."));
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
        ThinkingHud.stop();
        if (!open) return;

        if (err != null) {
            awaitingStitch = false;
            removeTrailingUserMessage();
            ChatDisplay.error(rootMessage(err));
            return;
        }

        // Extract and display any <think>...</think> reasoning blocks, then strip them.
        Matcher tm = THINK_BLOCK.matcher(reply);
        while (tm.find()) {
            String thought = tm.group(1).trim();
            if (!thought.isEmpty()) ChatDisplay.thought(thought);
        }
        reply = THINK_BLOCK.matcher(reply).replaceAll("").trim();

        // rougefix fence: a targeted block fix proposed by the AI from the signal trace.
        // Held as pendingFix until the player says "fix it"; stripped from displayed text.
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
                        + (WorldPlacer.isAvailable() ? "" : " (singleplayer only)")
                        + ", or \"no\" to skip.");
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Failed to parse rougefix: {}", e.getMessage(), e);
                history.add(ChatMessage.assistant(reply));
                ChatDisplay.print(reply);
            }
            return;
        }

        // Check for a planning outline fence first (takes priority over build fences).
        Matcher pm = PLANNING_FENCE.matcher(reply);
        if (pm.find() && mode != Mode.BUILDING) {
            String outlineJson = pm.group(1).trim();
            String chatPart = PLANNING_FENCE.matcher(reply).replaceAll("").trim();
            try {
                awaitingStitch = false; // the planning fence itself provides the preview + confirm
                pendingOutline = PlanningOutline.fromJson(outlineJson);
                mode = Mode.PLANNING;
                history.add(ChatMessage.assistant(chatPart.isEmpty() ? "[planning outline]" : chatPart));
                if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
                ChatDisplay.plan(pendingOutline);
                // Show preview hologram if the outline includes block data.
                if (!pendingOutline.preview().isEmpty()) {
                    net.minecraft.core.BlockPos anchor = StepSession.computeAnchorFor(pendingOutline.preview());
                    GhostRenderer.showPreview(anchor, pendingOutline.preview());
                }
            } catch (Exception e) {
                LOGGER.warn("[Rouge] Failed to parse planning outline: {}", e.getMessage(), e);
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

                history.add(ChatMessage.assistant(chatPart.isEmpty() ? "[build proposal]" : chatPart));

                if (awaitingStitch) {
                    // A stitched build: show it as a gold preview and wait for the player to confirm,
                    // rather than dropping straight into the step-by-step hologram.
                    awaitingStitch = false;
                    pendingOutline = null;
                    pendingPlan = plan;
                    mode = Mode.CONFIRM_BUILD;
                    showPreview(plan);
                    if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
                    int blocks = finalStepBlocks(plan).size();
                    ChatDisplay.system("Stitched a build — " + plan.steps().size() + " step"
                            + (plan.steps().size() == 1 ? "" : "s") + ", " + blocks
                            + " blocks (previewed in gold). Say \"yes\" to build it step by step, or \"no\" to discard.");
                } else {
                    pendingOutline = null;
                    GhostRenderer.clearPreview();
                    mode = Mode.BUILDING;
                    StepSession.start(plan, buildMode == BuildMode.PLACE);
                    if (!StepSession.isActive()) {
                        mode = Mode.CHAT;
                    }
                    if (!chatPart.isEmpty()) ChatDisplay.print(chatPart);
                }
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

    /** Final cumulative block list of a plan (its last step), or empty for an empty plan. */
    private static List<BlockEntry> finalStepBlocks(StepPlan plan) {
        List<StepPlan.Step> steps = plan.steps();
        return steps.isEmpty() ? List.of() : steps.get(steps.size() - 1).blocks();
    }

    /** Shows the plan's full footprint as a gold preview hologram anchored in front of the player. */
    private static void showPreview(StepPlan plan) {
        List<BlockEntry> blocks = finalStepBlocks(plan);
        if (blocks.isEmpty()) return;
        BlockPos anchor = StepSession.computeAnchorFor(blocks);
        GhostRenderer.showPreview(anchor, blocks);
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
