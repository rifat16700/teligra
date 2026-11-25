package org.telegram.custom.aicall;

import org.telegram.custom.utils.CustomLogger;

/**
 * Phase 2 local test provider (no backend required).
 * Generates a periodic soft 440 Hz chime pulse (300ms tone, 1700ms silence)
 * at 48 kHz mono 16-bit PCM into aiOutputBuffer so remote audio injection
 * can be verified end-to-end, while logging caller audio RMS levels.
 */
public final class LocalAudioTestProvider implements AiSessionProvider {
    private static final String TAG = "LocalAudioTestProvider";
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_MS = 10;
    private static final int SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_MS) / 1000; // 480
    private static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2; // 960 bytes
    private static final double TONE_HZ = 440.0;
    private static final double AMPLITUDE = 4500.0;

    private volatile boolean running = false;
    private Thread generatorThread;
    private long lastRmsLogMs = 0L;

    @Override
    public synchronized void startSession(AiCallSessionState state, String ownerData, PcmRingBuffer aiOutputBuffer) {
        stopSession();
        if (aiOutputBuffer == null) return;

        running = true;
        int ownerDataLen = ownerData != null ? ownerData.length() : 0;
        CustomLogger.i(TAG, "Started local audio test session | ownerDataChars=" + ownerDataLen);

        generatorThread = new Thread(() -> {
            byte[] frame = new byte[BYTES_PER_FRAME];
            long sampleIndex = 0;
            int cycleFrames = 0; // 200 frames = 2000ms (0..29 tone, 30..199 silence)
            long nextTickNs = System.nanoTime();

            while (running) {
                boolean emitTone = cycleFrames < 30;
                if (emitTone) {
                    for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
                        double angle = (2.0 * Math.PI * TONE_HZ * (sampleIndex + i)) / SAMPLE_RATE;
                        short sample = (short) (Math.sin(angle) * AMPLITUDE);
                        frame[i * 2] = (byte) (sample & 0xFF);
                        frame[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                    }
                    sampleIndex += SAMPLES_PER_FRAME;
                } else {
                    for (int i = 0; i < BYTES_PER_FRAME; i++) {
                        frame[i] = 0;
                    }
                }
                aiOutputBuffer.write(frame, 0, BYTES_PER_FRAME);

                cycleFrames = (cycleFrames + 1) % 200;
                nextTickNs += FRAME_MS * 1_000_000L;
                long sleepNs = nextTickNs - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException ignored) {
                        break;
                    }
                } else {
                    nextTickNs = System.nanoTime();
                }
            }
        }, "AiLocalTestAudioThread");
        generatorThread.start();
    }

    @Override
    public void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!running || pcm16 == null || length < 2) return;
        long now = System.currentTimeMillis();
        if (now - lastRmsLogMs < 2000L) return;
        lastRmsLogMs = now;

        long sumSq = 0;
        int samples = length / 2;
        for (int i = 0; i < samples; i++) {
            short s = (short) ((pcm16[i * 2] & 0xFF) | (pcm16[i * 2 + 1] << 8));
            sumSq += (long) s * s;
        }
        double rms = samples > 0 ? Math.sqrt(sumSq / (double) samples) : 0.0;
        CustomLogger.d(TAG, "Captured caller PCM | rate=" + sampleRate + " ch=" + channels + " rms=" + Math.round(rms));
    }

    @Override
    public void onBargeIn() {
        CustomLogger.d(TAG, "Barge-in signaled (local test)");
    }

    @Override
    public synchronized void stopSession() {
        running = false;
        if (generatorThread != null) {
            generatorThread.interrupt();
            generatorThread = null;
        }
    }
}
