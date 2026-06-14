package dev.dhanika.rouge.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays raw 16-bit little-endian mono PCM from ElevenLabs ({@code pcm_24000}).
 */
final class PcmAudioPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("rouge");

    private static final AudioFormat FORMAT = new AudioFormat(
            ElevenLabsConfig.SAMPLE_RATE, 16, 1, true, false);

    private static volatile SourceDataLine activeLine;
    private static volatile boolean stopRequested;

    private PcmAudioPlayer() {
    }

    static void requestStop() {
        stopRequested = true;
        SourceDataLine line = activeLine;
        if (line != null) {
            line.stop();
            line.flush();
        }
    }

    /**
     * Blocks until playback finishes or {@link #requestStop()} is called.
     */
    static void play(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;

        stopRequested = false;
        SourceDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(FORMAT);
            line.start();
            activeLine = line;

            int offset = 0;
            int chunk = 4096;
            while (offset < pcm.length && !stopRequested) {
                int len = Math.min(chunk, pcm.length - offset);
                line.write(pcm, offset, len);
                offset += len;
            }
        } catch (LineUnavailableException e) {
            LOGGER.warn("[Rouge] Audio output unavailable: {}", e.getMessage());
        } finally {
            if (line != null) {
                line.drain();
                line.stop();
                line.close();
            }
            if (activeLine == line) {
                activeLine = null;
            }
        }
    }
}
