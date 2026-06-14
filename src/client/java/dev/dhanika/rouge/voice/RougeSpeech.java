package dev.dhanika.rouge.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Speaks Rouge chat lines via ElevenLabs. Lines are queued and played in order.
 * Use {@link Tier} to keep utterances short and save API credits.
 */
public final class RougeSpeech {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    /** Max words spoken for AI intros and main replies. */
    private static final int MAX_INTRO_WORDS = 8;
    /** Max words for step instructions (matches AI prompt). */
    private static final int MAX_STEP_WORDS = 12;
    /** Max words for errors. */
    private static final int MAX_ERROR_WORDS = 8;

    /** Max words for step-complete praise. */
    private static final int MAX_PRAISE_WORDS = 4;

    public enum Tier {
        /** Do not speak (status lines, full text already in chat). */
        OFF,
        /** AI intro / reply — a few words only. */
        INTRO,
        /** Step instruction — the explanation line, kept short. */
        STEP,
        /** Step cleared — brief "good job" before next instructions. */
        PRAISE,
        /** Error — very short. */
        ERROR
    }

    private static ElevenLabsClient client;
    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static final AtomicBoolean enabled = new AtomicBoolean(true);
    private static Thread worker;

    private RougeSpeech() {
    }

    public static void init(ElevenLabsConfig config) {
        client = new ElevenLabsClient(config);
        if (!client.hasKey()) {
            LOGGER.info("[Rouge] No {} — chat will be text-only until you set it.",
                    ElevenLabsConfig.TOKEN_ENV_VAR);
            return;
        }
        startWorker();
        LOGGER.info("[Rouge] Voice enabled (ElevenLabs voice {}).", config.voiceId());
    }

    public static boolean isAvailable() {
        return client != null && client.hasKey();
    }

    public static boolean isEnabled() {
        return enabled.get();
    }

    public static void setEnabled(boolean on) {
        enabled.set(on);
        if (!on) {
            stop();
        }
    }

    /** Queue a line for speech with the given brevity tier. */
    public static void speak(String line, Tier tier) {
        if (tier == Tier.OFF || !enabled.get() || client == null || !client.hasKey() || line == null) {
            return;
        }
        String cleaned = forSpeech(line, tier);
        if (cleaned.isBlank()) {
            return;
        }
        queue.offer(cleaned);
    }

    public static void stop() {
        queue.clear();
        PcmAudioPlayer.requestStop();
    }

    public static void shutdown() {
        stop();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(RougeSpeech::runWorker, "rouge-tts");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String text = queue.take();
                byte[] pcm = client.synthesize(text).join();
                PcmAudioPlayer.play(pcm);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[Rouge] TTS worker skipped a line: {}", e.getMessage());
            }
        }
    }

    static String forSpeech(String line, Tier tier) {
        String t = line.trim();
        if (t.isEmpty()) return "";

        t = t.replaceAll("[`*_#]", "");
        t = t.replace("•", "");
        t = t.replace("✔", "");
        t = t.replaceAll("\\s+", " ").trim();

        int maxWords = switch (tier) {
            case INTRO -> MAX_INTRO_WORDS;
            case STEP -> MAX_STEP_WORDS;
            case PRAISE -> MAX_PRAISE_WORDS;
            case ERROR -> MAX_ERROR_WORDS;
            case OFF -> 0;
        };
        return limitWords(t, maxWords);
    }

    private static String limitWords(String text, int maxWords) {
        if (maxWords <= 0 || text.isBlank()) return "";
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }
}
