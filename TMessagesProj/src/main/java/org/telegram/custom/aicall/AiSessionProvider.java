package org.telegram.custom.aicall;

/**
 * Abstraction for the live AI voice backend (or Phase 2 local test provider).
 * Decouples WebRTC audio hooks from any specific AI transport/provider.
 */
public interface AiSessionProvider {
    void startSession(AiCallSessionState state, String ownerData, PcmRingBuffer aiOutputBuffer);

    void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels);

    void onBargeIn();

    void stopSession();
}
