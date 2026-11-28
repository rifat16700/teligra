package org.telegram.custom.aicall;

import android.util.Base64;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AndroidUtilities;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instant-Reply Dual-Model Orchestrator:
 * 1. Transcribe WS (gemini-3.5-transcribe-live, TEXT-only, Fast VAD + Noise Gate):
 *    Streams caller 16 kHz PCM continuously, shows live bubbles in AiCallOverlayView,
 *    and flushes finalized caller text immediately on server turnComplete OR 450ms silence.
 * 2. Speaker Voice WS (user-selected model e.g. gemini-3.8-live, pre-connected):
 *    Receives finalized caller text via clientContent(turnComplete=true) just like
 *    pressing Enter in the Live tester, returning crisp voice audio in ~0.5-1.5s!
 *
 * When Owner takes over, Speaker WS disconnects cleanly while Transcribe WS stays
 * running in the background so Return to AI has full Owner+Caller summary context.
 */
public final class DualModelOrchestrator implements AiSessionProvider {
    private static final String TAG = "DualModelOrchestrator";
    public static final String TRANSCRIBE_MODEL_ID = "gemini-3.5-transcribe-live";
    private static final int GEMINI_INPUT_RATE = 16000;
    private static final int GEMINI_DEFAULT_OUTPUT_RATE = 24000;
    private static final int CHUNK_BATCH_BYTES_16K = 1280; // 40ms at 16 kHz mono 16-bit
    private static final int MAX_INPUT_QUEUE = 100;
    private static final Pattern RATE_PATTERN = Pattern.compile("rate=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final class PcmChunk {
        final byte[] data;
        final int sampleRate;
        final int channels;

        PcmChunk(byte[] data, int sampleRate, int channels) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private final CallContextMemory memory;
    private final LinkedBlockingQueue<PcmChunk> transcribeInputQueue = new LinkedBlockingQueue<>(MAX_INPUT_QUEUE);

    private volatile boolean active = false;
    private volatile boolean transcribeSetupComplete = false;
    private volatile boolean speakerSetupComplete = false;
    private volatile int webrtcOutputSampleRate = 48000;
    private volatile int webrtcOutputChannels = 1;

    private AiCallSessionState sessionState;
    private PcmRingBuffer aiOutputBuffer;
    private String currentOwnerData = "";

    private WebSocket transcribeWs;
    private WebSocket speakerWs;
    private Thread transcribeThread;
    private Thread speakerConnectThread;

    private final StringBuilder pendingCallerTurn = new StringBuilder();

    public DualModelOrchestrator(CallContextMemory memory) {
        this.memory = memory;
    }

    public void setTargetOutputFormat(int sampleRate, int channels) {
        if (sampleRate > 0) this.webrtcOutputSampleRate = sampleRate;
        if (channels > 0) this.webrtcOutputChannels = channels;
    }

    public boolean isConnectedOrWarm() {
        return active && (transcribeWs != null || speakerWs != null);
    }

    @Override
    public synchronized void startSession(AiCallSessionState state, String ownerData, PcmRingBuffer aiOutputBuffer) {
        this.sessionState = state;
        this.aiOutputBuffer = aiOutputBuffer;
        this.currentOwnerData = ownerData != null ? ownerData : "";

        final String apiKey = AiCallConfig.getInstance().getApiKey();
        if (apiKey.isEmpty()) {
            CustomLogger.w(TAG, "Cannot start DualModelOrchestrator: API key is empty.");
            return;
        }

        if (!active || transcribeWs == null || !transcribeWs.isOpen()) {
            active = true;
            transcribeInputQueue.clear();
            synchronized (pendingCallerTurn) {
                pendingCallerTurn.setLength(0);
            }
            startTranscribeLoop(apiKey);
        }

        boolean wantAi = state != null && (state.isAiActive() || state.getMode() == AiCallSessionState.Mode.RINGING_TIMER);
        if (wantAi) {
            if (speakerWs == null || !speakerWs.isOpen()) {
                startOrRestartSpeaker(apiKey);
            }
        } else {
            stopSpeakerOnly();
        }
    }

    public void onOwnerLocalAudioPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !transcribeSetupComplete || sessionState == null || sessionState.isAiActive()) {
            return;
        }
        enqueueChunk(transcribeInputQueue, pcm16, length, sampleRate, channels);
    }

    @Override
    public void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !transcribeSetupComplete || pcm16 == null || length <= 0) {
            return;
        }
        enqueueChunk(transcribeInputQueue, pcm16, length, sampleRate, channels);
    }

    private static void enqueueChunk(LinkedBlockingQueue<PcmChunk> q, byte[] pcm16, int length, int sampleRate, int channels) {
        int safeLen = Math.min(length, pcm16.length);
        byte[] copy = new byte[safeLen];
        System.arraycopy(pcm16, 0, copy, 0, safeLen);
        PcmChunk chunk = new PcmChunk(copy, sampleRate > 0 ? sampleRate : 48000, Math.max(1, channels));
        if (!q.offer(chunk)) {
            q.poll();
            q.offer(chunk);
        }
    }

    @Override
    public void onBargeIn() {
        if (aiOutputBuffer != null) {
            aiOutputBuffer.clear();
        }
    }

    public synchronized void stopSpeakerOnly() {
        speakerSetupComplete = false;
        synchronized (pendingCallerTurn) {
            pendingCallerTurn.setLength(0);
        }
        if (speakerWs != null) {
            try {
                speakerWs.disconnect();
            } catch (Throwable ignored) {}
            speakerWs = null;
        }
        if (speakerConnectThread != null) {
            speakerConnectThread.interrupt();
            speakerConnectThread = null;
        }
        if (aiOutputBuffer != null) {
            aiOutputBuffer.clear();
        }
    }

    @Override
    public synchronized void stopSession() {
        active = false;
        transcribeSetupComplete = false;
        transcribeInputQueue.clear();
        stopSpeakerOnly();
        if (transcribeWs != null) {
            try {
                transcribeWs.disconnect();
            } catch (Throwable ignored) {}
            transcribeWs = null;
        }
        if (transcribeThread != null) {
            transcribeThread.interrupt();
            transcribeThread = null;
        }
    }

    // ---- 1. Fast Speech-to-Text Engine (gemini-3.5-transcribe-live) ----

    private void startTranscribeLoop(final String apiKey) {
        transcribeThread = new Thread(() -> {
            try {
                String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
                        + URLEncoder.encode(apiKey, "UTF-8");

                WebSocket ws = new WebSocketFactory().setConnectionTimeout(12000).createSocket(url);
                ws.setPingInterval(25000);
                ws.addListener(new WebSocketAdapter() {
                    @Override
                    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                        try {
                            JSONObject genConfig = new JSONObject();
                            genConfig.put("responseModalities", new JSONArray().put("TEXT"));

                            JSONObject setup = new JSONObject();
                            setup.put("model", "models/" + TRANSCRIBE_MODEL_ID);
                            setup.put("generationConfig", genConfig);
                            setup.put("inputAudioTranscription", new JSONObject());
                            setup.put("realtimeInputConfig", buildFastVadConfig());
                            String sttPrompt = "Transcribe only clear human speech accurately in Bengali, Banglish, or English. "
                                    + "Never guess or hallucinate words from background noise. "
                                    + "If human speech is present but too distorted by noise to understand, output only: [UNCLEAR]";
                            setup.put("systemInstruction", new JSONObject().put("parts",
                                    new JSONArray().put(new JSONObject().put("text", sttPrompt))));

                            websocket.sendText(new JSONObject().put("setup", setup).toString());
                        } catch (Throwable t) {
                            CustomLogger.e(TAG, "Transcribe setup error", t);
                        }
                    }

                    @Override
                    public void onTextMessage(WebSocket websocket, String text) {
                        handleTranscribeJson(text);
                    }

                    @Override
                    public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                        if (binary != null && binary.length > 0) {
                            handleTranscribeJson(new String(binary, StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void onError(WebSocket websocket, WebSocketException cause) {
                        CustomLogger.e(TAG, "Transcribe WS error", cause);
                    }

                    @Override
                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        transcribeSetupComplete = false;
                        CustomLogger.i(TAG, "Transcribe WS closed (byServer=" + closedByServer + ")");
                    }
                });

                this.transcribeWs = ws;
                ws.connect();

                ByteArrayOutputStream batch16k = new ByteArrayOutputStream(CHUNK_BATCH_BYTES_16K * 2);
                while (active && ws.isOpen()) {
                    PcmChunk chunk;
                    try {
                        chunk = transcribeInputQueue.poll(35, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        if (!active) break;
                        continue;
                    }
                    if (chunk == null) {
                        if (batch16k.size() > 0 && transcribeSetupComplete) {
                            byte[] out = batch16k.toByteArray();
                            sendPcm16kBatch(ws, out);
                            batch16k.reset();
                        }
                        continue;
                    }
                    byte[] mono = PcmResampler.toMono(chunk.data, chunk.data.length, chunk.channels);
                    byte[] pcm16k = PcmResampler.resampleMono(mono, mono.length, chunk.sampleRate, GEMINI_INPUT_RATE);
                    if (pcm16k.length > 0) {
                        batch16k.write(pcm16k, 0, pcm16k.length);
                        if (batch16k.size() >= CHUNK_BATCH_BYTES_16K && transcribeSetupComplete) {
                            byte[] out = batch16k.toByteArray();
                            sendPcm16kBatch(ws, out);
                            batch16k.reset();
                        }
                    }
                }
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Transcribe loop stopped", t);
            } finally {
                transcribeSetupComplete = false;
            }
        }, "DualTranscribeWsThread");
        transcribeThread.start();
    }

    private void handleTranscribeJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                transcribeSetupComplete = true;
                CustomLogger.i(TAG, "Fast STT ready (" + TRANSCRIBE_MODEL_ID + ")");
                return;
            }

            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            boolean isAiTurn = sessionState != null && sessionState.isAiActive();

            JSONObject interim = sc.optJSONObject("interimInputTranscription");
            if (interim != null && isAiTurn) {
                String txt = interim.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    // Interrupt stale AI playback immediately as soon as caller speaks
                    onBargeIn();
                }
            }

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    String role = isAiTurn ? "CALLER" : "OWNER";
                    memory.addOrAppendEntry(role, txt, true);
                    if (sessionState != null) sessionState.appendTranscript(role, txt);

                    if (isAiTurn) {
                        onBargeIn();
                        synchronized (pendingCallerTurn) {
                            if (pendingCallerTurn.length() > 0) pendingCallerTurn.append(' ');
                            pendingCallerTurn.append(txt.trim());
                        }
                    }
                }
            }

            if (sc.optBoolean("turnComplete", false) && isAiTurn) {
                flushPendingCallerTurn();
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error parsing transcribe JSON", t);
        }
    }

    private void flushPendingCallerTurn() {
        if (!active || sessionState == null || !sessionState.isAiActive()) return;
        final String finalizedCaller;
        synchronized (pendingCallerTurn) {
            finalizedCaller = pendingCallerTurn.toString().trim();
            pendingCallerTurn.setLength(0);
        }
        if (!finalizedCaller.isEmpty()) {
            new Thread(() -> forwardFinalTextToSpeaker(finalizedCaller), "ForwardTurnThread").start();
        }
    }

    // ---- 2. Pre-Connected Speaker Voice Model (e.g. gemini-3.8-live) ----

    private void startOrRestartSpeaker(final String apiKey) {
        stopSpeakerOnly();
        final AiCallConfig config = AiCallConfig.getInstance();
        final String modelId = config.getModelId();
        final String apiVersion = config.getApiVersion();
        final String voiceName = config.getVoiceName();
        final String thinkingLevel = config.getThinkingLevel();

        speakerConnectThread = new Thread(() -> {
            try {
                String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage."
                        + apiVersion
                        + ".GenerativeService.BidiGenerateContent?key="
                        + URLEncoder.encode(apiKey, "UTF-8");

                WebSocket ws = new WebSocketFactory().setConnectionTimeout(12000).createSocket(url);
                ws.setPingInterval(25000);
                ws.addListener(new WebSocketAdapter() {
                    @Override
                    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                        try {
                            String setupJson = buildSpeakerSetupJson(modelId, voiceName, thinkingLevel);
                            websocket.sendText(setupJson);
                        } catch (Throwable t) {
                            CustomLogger.e(TAG, "Speaker setup error", t);
                        }
                    }

                    @Override
                    public void onTextMessage(WebSocket websocket, String text) {
                        handleSpeakerJson(text);
                    }

                    @Override
                    public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                        if (binary != null && binary.length > 0) {
                            handleSpeakerJson(new String(binary, StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void onError(WebSocket websocket, WebSocketException cause) {
                        CustomLogger.e(TAG, "Speaker WS error", cause);
                    }

                    @Override
                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        speakerSetupComplete = false;
                        CustomLogger.i(TAG, "Speaker WS disconnected (byServer=" + closedByServer + ")");
                    }
                });

                this.speakerWs = ws;
                ws.connect();
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Failed connecting Speaker WS", t);
            }
        }, "DualSpeakerWsConnectThread");
        speakerConnectThread.start();
    }

    private void forwardFinalTextToSpeaker(String finalizedCallerText) {
        WebSocket ws = this.speakerWs;
        if (ws == null || !ws.isOpen() || !speakerSetupComplete) {
            CustomLogger.w(TAG, "Speaker WS not ready; dropping caller turn: " + finalizedCallerText);
            return;
        }
        try {
            if (aiOutputBuffer != null) {
                aiOutputBuffer.clear();
            }
            JSONObject part = new JSONObject().put("text", finalizedCallerText);
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            JSONObject clientContent = new JSONObject()
                    .put("turns", new JSONArray().put(turn))
                    .put("turnComplete", true);
            ws.sendText(new JSONObject().put("clientContent", clientContent).toString());
            CustomLogger.d(TAG, "Forwarded finalized text -> Speaker (" + AiCallConfig.getInstance().getModelId() + ")");
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Failed forwarding text to Speaker WS", t);
        }
    }

    private void handleSpeakerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                speakerSetupComplete = true;
                CustomLogger.i(TAG, "Speaker WS ready (" + AiCallConfig.getInstance().getModelId() + ")");
                return;
            }

            JSONObject toolCall = data.optJSONObject("toolCall");
            if (toolCall != null) {
                JSONArray fnCalls = toolCall.optJSONArray("functionCalls");
                if (fnCalls != null) {
                    for (int i = 0; i < fnCalls.length(); i++) {
                        JSONObject fn = fnCalls.optJSONObject(i);
                        if (fn != null && "end_call".equals(fn.optString("name"))) {
                            CustomLogger.i(TAG, "Speaker requested end_call; scheduling hang-up.");
                            AndroidUtilities.runOnUIThread(() -> AiCallActionBus.dispatch(AiCallActionBus.ACTION_END_CALL, null), 1800L);
                        }
                    }
                }
            }

            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            if (sc.optBoolean("interrupted", false)) {
                onBargeIn();
            }

            JSONObject outTrans = sc.optJSONObject("outputTranscription");
            if (outTrans != null) {
                String txt = outTrans.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    memory.addOrAppendEntry("AI", txt, true);
                    if (sessionState != null) sessionState.appendTranscript("AI", txt);
                }
            }

            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        String textPart = part.optString("text", "");
                        if (!textPart.trim().isEmpty() && !part.optBoolean("thought", false)) {
                            memory.addOrAppendEntry("AI", textPart, true);
                        }
                        JSONObject inlineData = part.optJSONObject("inlineData");
                        if (inlineData != null) {
                            String mimeType = inlineData.optString("mimeType", "");
                            String b64 = inlineData.optString("data", "");
                            if (mimeType.startsWith("audio/") && !b64.isEmpty() && aiOutputBuffer != null) {
                                int inRate = parseSampleRate(mimeType, GEMINI_DEFAULT_OUTPUT_RATE);
                                byte[] pcm = Base64.decode(b64, Base64.DEFAULT);
                                byte[] resampledMono = PcmResampler.resampleMono(pcm, pcm.length, inRate, webrtcOutputSampleRate);
                                byte[] channelMatched = PcmResampler.monoToChannels(resampledMono, resampledMono.length, webrtcOutputChannels);
                                if (channelMatched.length > 0) {
                                    aiOutputBuffer.write(channelMatched, 0, channelMatched.length);
                                }
                            }
                        }
                    }
                }
            }

            if (sc.optBoolean("turnComplete", false) && aiOutputBuffer != null) {
                aiOutputBuffer.flushForPlayback();
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error parsing speaker JSON", t);
        }
    }

    private String buildSpeakerSetupJson(String modelId, String voiceName, String thinkingLevel) throws Exception {
        JSONObject generationConfig = new JSONObject();
        generationConfig.put("responseModalities", new JSONArray().put("AUDIO"));
        generationConfig.put("speechConfig", new JSONObject().put("voiceConfig",
                new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voiceName))));

        if ("LOW".equals(thinkingLevel) || "HIGH".equals(thinkingLevel)) {
            generationConfig.put("thinkingConfig", new JSONObject().put("thinkingLevel", thinkingLevel));
        }

        JSONObject setup = new JSONObject();
        setup.put("model", modelId.startsWith("models/") ? modelId : "models/" + modelId);
        setup.put("generationConfig", generationConfig);
        setup.put("outputAudioTranscription", new JSONObject());
        if (modelId.contains("gemini-2.5")) {
            setup.put("enableAffectiveDialog", true);
        }

        String prompt = memory.buildHandoverContextPrompt(currentOwnerData);
        setup.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", prompt))));

        JSONObject endCallDecl = new JSONObject()
                .put("name", "end_call")
                .put("description", "End the phone call politely once the conversation is finished or the caller says goodbye.");
        setup.put("tools", new JSONArray().put(
                new JSONObject().put("functionDeclarations", new JSONArray().put(endCallDecl))));

        return new JSONObject().put("setup", setup).toString();
    }

    static JSONObject buildFastVadConfig() throws Exception {
        JSONObject vad = new JSONObject();
        vad.put("disabled", false);
        vad.put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW");
        vad.put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH");
        vad.put("silenceDurationMs", 350);
        return new JSONObject().put("automaticActivityDetection", vad);
    }

    private static void sendPcm16kBatch(WebSocket ws, byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0 || ws == null || !ws.isOpen()) return;
        try {
            JSONObject audioObj = new JSONObject();
            audioObj.put("mimeType", "audio/pcm;rate=16000");
            audioObj.put("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP));
            JSONObject payload = new JSONObject();
            payload.put("realtimeInput", new JSONObject().put("audio", audioObj));
            ws.sendText(payload.toString());
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error sending transcribe PCM chunk", t);
        }
    }

    private static int parseSampleRate(String mimeType, int fallback) {
        if (mimeType == null) return fallback;
        Matcher m = RATE_PATTERN.matcher(mimeType);
        if (m.find()) {
            try {
                int rate = Integer.parseInt(m.group(1));
                if (rate > 0) return rate;
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
