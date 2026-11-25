package org.telegram.custom.aicall;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, composable runtime state for an active or ringing call.
 * Independent capability flags allow AI handling, anonymous monitoring,
 * background recording, and transcript buffering to work simultaneously
 * or be toggled dynamically mid-call without interfering with one another.
 */
public final class AiCallSessionState {

    public enum Mode {
        IDLE,
        RINGING_TIMER,
        AI_HANDLING,
        OWNER_HANDLING
    }

    public enum CallDirection {
        INCOMING,
        OUTGOING
    }

    private volatile Mode mode = Mode.IDLE;
    private volatile CallDirection direction = CallDirection.INCOMING;
    private volatile long callId = 0L;
    private volatile int account = 0;
    private volatile long peerUserId = 0L;
    private volatile boolean video = false;
    private volatile long startedAtMs = 0L;

    // Composable capability flags
    private volatile boolean aiActive = false;
    private volatile boolean anonymousListenIn = false;
    private volatile boolean recordingActive = false;
    private volatile boolean transcriptBuffering = false;

    private final CopyOnWriteArrayList<String> transcriptBuffer = new CopyOnWriteArrayList<>();

    public synchronized void reset() {
        mode = Mode.IDLE;
        direction = CallDirection.INCOMING;
        callId = 0L;
        account = 0;
        peerUserId = 0L;
        video = false;
        startedAtMs = 0L;
        aiActive = false;
        anonymousListenIn = false;
        recordingActive = false;
        transcriptBuffering = false;
        transcriptBuffer.clear();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public CallDirection getDirection() {
        return direction;
    }

    public void setDirection(CallDirection direction) {
        this.direction = direction;
    }

    public long getCallId() {
        return callId;
    }

    public void setCallId(long callId) {
        this.callId = callId;
    }

    public int getAccount() {
        return account;
    }

    public void setAccount(int account) {
        this.account = account;
    }

    public long getPeerUserId() {
        return peerUserId;
    }

    public void setPeerUserId(long peerUserId) {
        this.peerUserId = peerUserId;
    }

    public boolean isVideo() {
        return video;
    }

    public void setVideo(boolean video) {
        this.video = video;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public void setStartedAtMs(long startedAtMs) {
        this.startedAtMs = startedAtMs;
    }

    public boolean isAiActive() {
        return aiActive;
    }

    public void setAiActive(boolean aiActive) {
        this.aiActive = aiActive;
    }

    public boolean isAnonymousListenIn() {
        return anonymousListenIn;
    }

    public void setAnonymousListenIn(boolean anonymousListenIn) {
        this.anonymousListenIn = anonymousListenIn;
    }

    public boolean isRecordingActive() {
        return recordingActive;
    }

    public void setRecordingActive(boolean recordingActive) {
        this.recordingActive = recordingActive;
    }

    public boolean isTranscriptBuffering() {
        return transcriptBuffering;
    }

    public void setTranscriptBuffering(boolean transcriptBuffering) {
        this.transcriptBuffering = transcriptBuffering;
    }

    public void appendTranscript(String speaker, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String label = (speaker == null || speaker.trim().isEmpty()) ? "Unknown" : speaker.trim();
        transcriptBuffer.add("[" + label + "]: " + text.trim());
    }

    public List<String> getTranscriptSnapshot() {
        return Collections.unmodifiableList(transcriptBuffer);
    }

    public String getFormattedTranscript() {
        if (transcriptBuffer.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transcriptBuffer.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(transcriptBuffer.get(i));
        }
        return sb.toString();
    }
}
