package org.telegram.custom.aicall;

import org.telegram.custom.utils.CustomLogger;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Real-time Java audio bridge between WebRtcAudioTrack / WebRtcAudioRecord,
 * DualModelOrchestrator (or single/local fallback), standalone AiMonitorPlayer
 * (AEC-safe anonymous listening), and CallAudioRecorder.
 */
public final class AiAudioBridge {
    private static final String TAG = "AiAudioBridge";
    private static final int SCRATCH_MAX_BYTES = 8192;

    private static AiAudioBridge instance;

    private final CallContextMemory memory = new CallContextMemory();
    private final PcmRingBuffer aiOutputBuffer = new PcmRingBuffer();
    private final CallAudioRecorder recorder = new CallAudioRecorder();
    private final AiMonitorPlayer monitorPlayer = new AiMonitorPlayer();

    private final DualModelOrchestrator dualOrchestrator = new DualModelOrchestrator(memory);
    private final GeminiLiveSessionProvider singleProvider = new GeminiLiveSessionProvider();
    private final LocalAudioTestProvider localTestProvider = new LocalAudioTestProvider();
    private volatile AiSessionProvider sessionProvider = localTestProvider;
    private volatile long prewarmedCallId = -1L;

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

    public CallContextMemory getMemory() {
        return memory;
    }

    public PcmRingBuffer getAiOutputBuffer() {
        return aiOutputBuffer;
    }

    private AiSessionProvider selectActiveSessionProvider() {
        AiCallConfig config = AiCallConfig.getInstance();
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            this.sessionProvider = localTestProvider;
            CustomLogger.w(TAG, "No Gemini API key configured locally; falling back to LocalAudioTestProvider.");
        } else if (config.isDualModelEnabled()) {
            this.sessionProvider = dualOrchestrator;
        } else {
            this.sessionProvider = singleProvider;
        }
        return this.sessionProvider;
    }

    public synchronized void prewarmForIncomingCall(AiCallSessionState state, String ownerData, String peerName) {
        if (state == null) return;
        String apiKey = AiCallConfig.getInstance().getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) return;
        long cid = state.getCallId();
        if (cid != 0L && prewarmedCallId == cid) return;

        memory.reset(cid, state.getPeerUserId(), peerName);
        prewarmedCallId = cid;
        AiSessionProvider activeProvider = selectActiveSessionProvider();
        activeProvider.startSession(state, ownerData, aiOutputBuffer);
        CustomLogger.i(TAG, "Pre-warmed WebSocket during ringing | serial=" + memory.getSerialId()
                + " | provider=" + activeProvider.getClass().getSimpleName());
    }

    public synchronized void cancelPrewarm() {
        if (prewarmedCallId == -1L) return;
        prewarmedCallId = -1L;
        dualOrchestrator.stopSession();
        singleProvider.stopSession();
        aiOutputBuffer.clear();
    }

    public synchronized void onCallStarted(AiCallSessionState state, String ownerData, String peerName) {
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
        monitorPlayer.stop();
        if (state == null) return;

        boolean alreadyPrewarmed = (prewarmedCallId != -1L && prewarmedCallId == state.getCallId());
        prewarmedCallId = -1L;
        if (!alreadyPrewarmed) {
            memory.reset(state.getCallId(), state.getPeerUserId(), peerName);
        }

        if (state.isRecordingActive()) {
            recorder.start(state.getCallId(), 48000);
        }

        AiSessionProvider activeProvider = selectActiveSessionProvider();
        if (state.isAiActive() || activeProvider == dualOrchestrator) {
            if (!alreadyPrewarmed || !(activeProvider == dualOrchestrator && dualOrchestrator.isConnectedOrWarm())) {
                activeProvider.startSession(state, ownerData, aiOutputBuffer);
            }
        }
        if (state.isAiActive() && state.isAnonymousListenIn()) {
            monitorPlayer.start(48000);
        }
        CustomLogger.i(TAG, "Audio bridge started | serial=" + memory.getSerialId()
                + " | prewarmed=" + alreadyPrewarmed
                + " | ai=" + state.isAiActive()
                + " | provider=" + activeProvider.getClass().getSimpleName());
    }

    public synchronized void onHandoverToAi(AiCallSessionState state, String ownerData) {
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;
        memory.recordHandoverToAi(null);

        if (state != null) {
            AiSessionProvider activeProvider = selectActiveSessionProvider();
            activeProvider.startSession(state, ownerData, aiOutputBuffer);
            if (state.isAnonymousListenIn()) {
                monitorPlayer.start(48000);
            }
        }
    }

    public synchronized void onTakeOverByOwner(AiCallSessionState state) {
        memory.recordTakeOverFromAi(null);
        monitorPlayer.stop();
        aiOutputBuffer.clear();
        lastAiFrameLen = 0;

        if (sessionProvider == dualOrchestrator) {
            dualOrchestrator.stopSpeakerOnly();
        } else {
            sessionProvider.stopSession();
        }
    }

    public synchronized void updateAnonymousListenState(AiCallSessionState state) {
        if (state != null && state.isAiActive() && state.isAnonymousListenIn()) {
            monitorPlayer.start(48000);
        } else {
            monitorPlayer.stop();
        }
    }

    public synchronized File onCallEnded() {
        monitorPlayer.stop();
        dualOrchestrator.stopSession();
        singleProvider.stopSession();
        localTestProvider.stopSession();
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
            byte[] monoRemote = PcmResampler.toMono(playoutScratch, len, channels);
            recorder.onRemotePcm(monoRemote, monoRemote.length, sampleRate);
        }

        if (state.isAiActive()) {
            sessionProvider.onCallerPcm(playoutScratch, len, sampleRate, channels);

            // ALWAYS zero WebRtcAudioTrack while AI is active so Android/WebRTC AEC
            // never cancels AI's voice when Anonymous Listen-In is enabled!
            if (state.isAnonymousListenIn()) {
                monitorPlayer.start(sampleRate > 0 ? sampleRate : 48000);
                byte[] monoCaller = PcmResampler.toMono(playoutScratch, len, channels);
                monitorPlayer.enqueueMixedFrame(monoCaller, lastAiFrame, lastAiFrameLen, monoCaller.length);
            } else {
                monitorPlayer.stop();
            }

            Arrays.fill(playoutScratch, 0, len, (byte) 0);
            byteBuffer.position(0);
            byteBuffer.put(playoutScratch, 0, len);
            byteBuffer.position(0);
        } else {
            monitorPlayer.stop();
            if (sessionProvider == dualOrchestrator && state.isTranscriptBuffering()) {
                dualOrchestrator.onCallerPcm(playoutScratch, len, sampleRate, channels);
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
        if (sampleRate > 0) {
            dualOrchestrator.setTargetOutputFormat(sampleRate, Math.max(1, channels));
            singleProvider.setTargetOutputSampleRate(sampleRate);
            singleProvider.setTargetOutputChannels(Math.max(1, channels));
        }
        int len = Math.min(sizeInBytes, SCRATCH_MAX_BYTES);

        if (state.isAiActive()) {
            aiOutputBuffer.read(recordScratch, 0, len);

            byte[] monoAi = PcmResampler.toMono(recordScratch, len, channels);
            int monoLen = Math.min(monoAi.length, SCRATCH_MAX_BYTES);
            System.arraycopy(monoAi, 0, lastAiFrame, 0, monoLen);
            lastAiFrameLen = monoLen;

            byteBuffer.position(0);
            byteBuffer.put(recordScratch, 0, len);
            byteBuffer.position(0);

            if (state.isRecordingActive()) {
                recorder.onLocalPcm(monoAi, monoLen, sampleRate);
            }
        } else {
            boolean needCopy = state.isRecordingActive() || (sessionProvider == dualOrchestrator && state.isTranscriptBuffering());
            if (needCopy) {
                byteBuffer.position(0);
                byteBuffer.get(recordScratch, 0, len);
                byteBuffer.position(0);
                if (state.isRecordingActive()) {
                    byte[] monoLocal = PcmResampler.toMono(recordScratch, len, channels);
                    recorder.onLocalPcm(monoLocal, monoLocal.length, sampleRate);
                }
                if (sessionProvider == dualOrchestrator && state.isTranscriptBuffering()) {
                    dualOrchestrator.onOwnerLocalAudioPcm(recordScratch, len, sampleRate, channels);
                }
            }
        }
    }
}
