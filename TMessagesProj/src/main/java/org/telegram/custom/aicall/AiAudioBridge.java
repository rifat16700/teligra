package org.telegram.custom.aicall;

import org.telegram.custom.utils.CustomLogger;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Real-time Java audio bridge between WebRtcAudioTrack / WebRtcAudioRecord,
 * the active AiSessionProvider, anonymous live monitoring, and CallAudioRecorder.
 */
public final class AiAudioBridge {
    private static final String TAG = "AiAudioBridge";
    private static final int SCRATCH_MAX_BYTES = 8192;

    private static AiAudioBridge instance;

    private final PcmRingBuffer aiOutputBuffer = new PcmRingBuffer();
    private final CallAudioRecorder recorder = new CallAudioRecorder();
    private volatile AiSessionProvider sessionProvider = new LocalAudioTestProvider();

    private final byte[] playoutScratch = new byte[SCRATCH_MAX_BYTES];
    private final byte[] recordScratch = new byte[SCRATCH_MAX_BYTES];
    private final byte[] lastAiFrame = new byte[SCRATCH_MAX_BYTES];
    private volatile int lastAiFrameLen = 0;

    private AiAudioBridge() {}

    public static synchronized AiAudioBridge getInstance() {
        if (instance == null) {
            instance = new AiAudioBridge();
        }
        return instance;
    }

    public void setSessionProvider(AiSessionProvider provider) {
        if (provider != null) {
            this.sessionProvider = provider;
        }
    }

    public PcmRingBuffer getAiOutputBuffer() {
        return aiOutputBuffer;
    }

    public synchronized void onCallStarted(AiCallSessionState state, String ownerData) {
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
        if (state == null) return;

        if (state.isRecordingActive()) {
            recorder.start(state.getCallId(), 48000);
        }
        if (state.isAiActive()) {
            sessionProvider.startSession(state, ownerData, aiOutputBuffer);
        }
        CustomLogger.i(TAG, "Audio bridge started | ai=" + state.isAiActive()
                + " | record=" + state.isRecordingActive()
                + " | listenIn=" + state.isAnonymousListenIn());
    }

    public synchronized void onHandoverToAi(AiCallSessionState state, String ownerData) {
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
        if (state != null) {
            sessionProvider.startSession(state, ownerData, aiOutputBuffer);
        }
    }

    public synchronized void onTakeOverByOwner() {
        sessionProvider.stopSession();
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
    }

    public synchronized File onCallEnded() {
        sessionProvider.stopSession();
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
        return recorder.stopAndFinalize();
    }

    public long getLastRecordedDurationSeconds() {
        return recorder.getDurationSeconds();
    }

    /**
     * Called on WebRtcAudioTrack's AudioTrackThread immediately after nativeGetPlayoutData().
     */
    public void onWebRtcPlayout(ByteBuffer byteBuffer, int sizeInBytes, int sampleRate, int channels) {
        AiCallSessionState state = AiCallController.getInstance().getState();
        if (state.getMode() == AiCallSessionState.Mode.IDLE || byteBuffer == null || sizeInBytes <= 0) {
            return;
        }
        int len = Math.min(sizeInBytes, SCRATCH_MAX_BYTES);

        byteBuffer.position(0);
        byteBuffer.get(playoutScratch, 0, len);
        byteBuffer.position(0);

        if (state.isRecordingActive()) {
            recorder.onRemotePcm(playoutScratch, len, sampleRate);
        }

        if (state.isAiActive()) {
            sessionProvider.onCallerPcm(playoutScratch, len, sampleRate, channels);
            if (!state.isAnonymousListenIn()) {
                // Mute local phone speaker while AI handles the call
                Arrays.fill(playoutScratch, 0, len, (byte) 0);
                byteBuffer.position(0);
                byteBuffer.put(playoutScratch, 0, len);
                byteBuffer.position(0);
            } else if (lastAiFrameLen >= len) {
                // Mix AI reply at 50% volume so owner hears both sides anonymously
                mixHalfInPlace(playoutScratch, lastAiFrame, len);
                byteBuffer.position(0);
                byteBuffer.put(playoutScratch, 0, len);
                byteBuffer.position(0);
            }
        }
    }

    /**
     * Called on WebRtcAudioRecord's AudioRecordThread immediately before nativeDataIsRecorded().
     */
    public void onWebRtcRecord(ByteBuffer byteBuffer, int sizeInBytes, int sampleRate, int channels) {
        AiCallSessionState state = AiCallController.getInstance().getState();
        if (state.getMode() == AiCallSessionState.Mode.IDLE || byteBuffer == null || sizeInBytes <= 0) {
            return;
        }
        int len = Math.min(sizeInBytes, SCRATCH_MAX_BYTES);

        if (state.isAiActive()) {
            aiOutputBuffer.read(recordScratch, 0, len);
            System.arraycopy(recordScratch, 0, lastAiFrame, 0, len);
            lastAiFrameLen = len;

            byteBuffer.position(0);
            byteBuffer.put(recordScratch, 0, len);
            byteBuffer.position(0);

            if (state.isRecordingActive()) {
                recorder.onLocalPcm(recordScratch, len, sampleRate);
            }
        } else if (state.isRecordingActive()) {
            byteBuffer.position(0);
            byteBuffer.get(recordScratch, 0, len);
            byteBuffer.position(0);
            recorder.onLocalPcm(recordScratch, len, sampleRate);
        }
    }

    private static void mixHalfInPlace(byte[] dst, byte[] ai, int byteLen) {
        int samples = byteLen / 2;
        for (int i = 0; i < samples; i++) {
            int idx = i * 2;
            short d = (short) ((dst[idx] & 0xFF) | (dst[idx + 1] << 8));
            short a = (short) ((ai[idx] & 0xFF) | (ai[idx + 1] << 8));
            int mixed = d + (a / 2);
            if (mixed > 32767) mixed = 32767;
            else if (mixed < -32768) mixed = -32768;
            dst[idx] = (byte) (mixed & 0xFF);
            dst[idx + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
    }
}
