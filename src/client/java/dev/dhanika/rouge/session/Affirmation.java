package dev.dhanika.rouge.session;

import java.util.Set;

/**
 * Lightweight classifier for the short replies a player types at a confirmation prompt
 * ("build it? yes/no", "say next when ready"). Anything that isn't a clear yes/next or a
 * clear no/stop is treated as {@link #OTHER} so it can be routed to the AI as a real
 * question instead of being misread as a command.
 */
public enum Affirmation {
    YES, NO, OTHER;

    private static final Set<String> YES_WORDS = Set.of(
            "y", "yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "k",
            "next", "ready", "done", "continue", "go", "proceed", "build", "build it",
            "lets go", "let's go", "lgtm", "do it", "start", "begin", "next step", "got it");

    private static final Set<String> NO_WORDS = Set.of(
            "n", "no", "nope", "nah", "stop", "cancel", "exit", "quit", "abort",
            "not yet", "wait", "hold on", "nevermind", "never mind", "back");

    public static Affirmation of(String text) {
        if (text == null) return OTHER;
        String t = text.trim().toLowerCase()
                .replaceAll("[!.?,]+$", "")   // drop trailing punctuation
                .trim();
        if (t.isEmpty()) return OTHER;
        if (YES_WORDS.contains(t)) return YES;
        if (NO_WORDS.contains(t)) return NO;
        return OTHER;
    }
}
