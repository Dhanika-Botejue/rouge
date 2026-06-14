package dev.dhanika.rouge.voice;

/**
 * Configuration for ElevenLabs text-to-speech.
 * <p>
 * The API key is read from {@link #TOKEN_ENV_VAR}. An optional voice id can be set with
 * {@link #VOICE_ENV_VAR}; otherwise a sensible default voice is used.
 */
public final class ElevenLabsConfig {

    public static final String TOKEN_ENV_VAR = "ELEVENLABS_API_KEY";
    public static final String VOICE_ENV_VAR = "ELEVENLABS_VOICE_ID";

    /** ElevenLabs TTS endpoint (voice id appended). */
    public static final String TTS_BASE = "https://api.elevenlabs.io/v1/text-to-speech/";

    /** Fast, natural model suitable for short tutor lines. */
    public static final String MODEL_ID = "eleven_turbo_v2_5";

    /** Raw 16-bit mono PCM at 24 kHz — playable with Java Sound without MP3 decoding. */
    public static final String OUTPUT_FORMAT = "pcm_24000";

    public static final int SAMPLE_RATE = 24_000;

    /** Sarah — premade voice that works on free-tier API (library voices like Rachel require paid). */
    public static final String DEFAULT_VOICE_ID = "EXAVITQu4vr4xnSDxMaL";

    private final String token;
    private final String voiceId;

    public ElevenLabsConfig() {
        String env = System.getenv(TOKEN_ENV_VAR);
        this.token = env == null ? "" : env.trim();

        String voice = System.getenv(VOICE_ENV_VAR);
        if (voice == null || voice.isBlank()) {
            this.voiceId = DEFAULT_VOICE_ID;
        } else {
            this.voiceId = voice.trim();
        }
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    public String token() {
        return token;
    }

    public String voiceId() {
        return voiceId;
    }

    public String endpoint() {
        return TTS_BASE + voiceId + "?output_format=" + OUTPUT_FORMAT;
    }
}
