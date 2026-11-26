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
 * Dual-Model Multitasking AI Call Pipeline:
 * 1. Listener/Transcriber WS (gemini-2.5-flash-native-audio-preview-12-2025):
 *    Streams caller audio, displays live transcript in chat UI, waits for
 *    Gemini server turnComplete (end-of-turn detection), and forwards finalized
 *    caller text to the Speaker model.
 * 2. Speaker Voice WS (user-selected model e.g. gemini-3.8-live):
 *    Receives finalized caller text + Owner Data + multi-handover summaries,
 *    returns crisp 24 kHz voice PCM resampled & channel-matched for WebRTC.
 */
public final class DualModelOrchestrator implements AiSessionProvider {
    private static final String TAG = "DualModelOrchestrator";
    public static final String LISTENER_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025";
    private static final int GEMINI_INPUT_RATE = 16000;
    private static final int GEMINI_DEFAULT_OUTPUT_RATE = 24000;
    private static final int CHUNK_BATCH_BYTES_16K = 3200; // 100ms at 16 kHz mono 16-bit
    private static final int MAX_INPUT_QUEUE = 120;
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
    private final LinkedBlockingQueue<PcmChunk> listenerQueue = new LinkedBlockingQueue<>(MAX_INPUT_QUEUE);

    private volatile boolean active = false;
    private volatile boolean listenerSetupComplete = false;
    private volatile boolean speakerSetupComplete = false;
    private volatile int webrtcOutputSampleRate = 48000;
    private volatile int webrtcOutputChannels = 1;

    private AiCallSessionState sessionState;
    private PcmRingBuffer aiOutputBuffer;
    private String currentOwnerData = "";

    private WebSocket listenerWs;
    private WebSocket speakerWs;
    private Thread listenerThread;
    private Thread speakerConnectThread;

    private final StringBuilder pendingCallerTurn = new StringBuilder();
    private final StringBuilder pendingOwnerTurn = new StringBuilder();

    public DualModelOrchestrator(CallContextMemory memory) {
        this.memory = memory;
    }

    public void setTargetOutputFormat(int sampleRate, int channels) {
        if (sampleRate > 0) this.webrtcOutputSampleRate = sampleRate;
        if (channels > 0) this.webrtcOutputChannels = channels;
    }

    @Override
    public synchronized void startSession(AiCallSessionState state, String ownerData, PcmRingBuffer aiOutputBuffer) {
        this.sessionState = state;
        this.aiOutputBuffer = aiOutputBuffer;
        this.currentOwnerData = ownerData != null ? ownerData : "";

        String apiKey = AiCallConfig.getInstance().getApiKey();
        if (apiKey.isEmpty()) {
            CustomLogger.w(TAG, "Cannot start DualModelOrchestrator: API key is empty.");
            return;
        }

        if (!active) {
            active = true;
            listenerQueue.clear();
            synchronized (pendingCallerTurn) {
                pendingCallerTurn.setLength(0);
            }
            synchronized (pendingOwnerTurn) {
                pendingOwnerTurn.setLength(0);
            }
            startListenerLoop(apiKey);
        }

        if (state != null && state.isAiActive()) {
            startOrRestartSpeaker(apiKey);
        } else {
            stopSpeakerOnly();
        }
    }

    public synchronized void onOwnerLocalAudioPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !listenerSetupComplete || sessionState == null || sessionState.isAiActive()) {
            return;
        }
        enqueueToListener(pcm16, length, sampleRate, channels);
    }

    @Override
    public void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !listenerSetupComplete || pcm16 == null || length <= 0) {
            return;
        }
        enqueueToListener(pcm16, length, sampleRate, channels);
    }

    private void enqueueToListener(byte[] pcm16, int length, int sampleRate, int channels) {
        int safeLen = Math.min(length, pcm16.length);
        byte[] copy = new byte[safeLen];
        System.arraycopy(pcm16, 0, copy, 0, safeLen);
        PcmChunk chunk = new PcmChunk(copy, sampleRate > 0 ? sampleRate : 48000, Math.max(1, channels));
        if (!listenerQueue.offer(chunk)) {
            listenerQueue.poll();
            listenerQueue.offer(chunk);
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
        listenerSetupComplete = false;
        listenerQueue.clear();
        stopSpeakerOnly();
        if (listenerWs != null) {
            try {
                listenerWs.disconnect();
            } catch (Throwable ignored) {}
            listenerWs = null;
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
    }

    // ---- 1. Listener / Transcriber / End-of-Turn Engine (2.5 Flash Native Audio) ----

    private void startListenerLoop(final String apiKey) {
        listenerThread = new Thread(() -> {
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
                            genConfig.put("responseModalities", new JSONArray().put("AUDIO"));
                            JSONObject setup = new JSONObject();
                            setup.put("model", "models/" + LISTENER_MODEL_ID);
                            setup.put("generationConfig", genConfig);
                            setup.put("inputAudioTranscription", new JSONObject());
                            setup.put("outputAudioTranscription", new JSONObject());
                            String sys = "You are a silent speech listener and transcript normalizer for a phone call. "
                                    + "Listen carefully and wait until the speaker finishes their sentence.";
                            setup.put("systemInstruction", new JSONObject().put("parts",
                                    new JSONArray().put(new JSONObject().put("text", sys))));
                            websocket.sendText(new JSONObject().put("setup", setup).toString());
                        } catch (Throwable t) {
                            CustomLogger.e(TAG, "Listener setup error", t);
                        }
                    }

                    @Override
                    public void onTextMessage(WebSocket websocket, String text) {
                        handleListenerJson(text);
                    }

                    @Override
                    public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                        if (binary != null && binary.length > 0) {
                            handleListenerJson(new String(binary, StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void onError(WebSocket websocket, WebSocketException cause) {
                        CustomLogger.e(TAG, "Listener WS error", cause);
                    }

                    @Override
                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        listenerSetupComplete = false;
                        CustomLogger.i(TAG, "Listener WS disconnected (byServer=" + closedByServer + ")");
                    }
                });

                this.listenerWs = ws;
                ws.connect();

                ByteArrayOutputStream batch16k = new ByteArrayOutputStream(CHUNK_BATCH_BYTES_16K * 2);
                while (active && ws.isOpen()) {
                    PcmChunk chunk;
                    try {
                        chunk = listenerQueue.poll(60, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        if (!active) break;
                        continue;
                    }
                    if (chunk == null) {
                        if (batch16k.size() > 0 && listenerSetupComplete) {
                            sendPcm16kBatch(ws, batch16k.toByteArray());
                            batch16k.reset();
                        }
                        continue;
                    }
                    byte[] mono = PcmResampler.toMono(chunk.data, chunk.data.length, chunk.channels);
                    byte[] pcm16k = PcmResampler.resampleMono(mono, mono.length, chunk.sampleRate, GEMINI_INPUT_RATE);
                    if (pcm16k.length > 0) {
                        batch16k.write(pcm16k, 0, pcm16k.length);
                        if (batch16k.size() >= CHUNK_BATCH_BYTES_16K && listenerSetupComplete) {
                            sendPcm16kBatch(ws, batch16k.toByteArray());
                            batch16k.reset();
                        }
                    }
                }
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Listener loop error", t);
            } finally {
                listenerSetupComplete = false;
            }
        }, "DualListenerWsThread");
        listenerThread.start();
    }

    private void handleListenerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                listenerSetupComplete = true;
                CustomLogger.i(TAG, "Listener WS ready (" + LISTENER_MODEL_ID + ")");
                return;
            }

            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            boolean isAiTurn = sessionState != null && sessionState.isAiActive();

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    if (isAiTurn) {
                        synchronized (pendingCallerTurn) {
                            if (pendingCallerTurn.length() > 0) pendingCallerTurn.append(' ');
                            pendingCallerTurn.append(txt.trim());
                        }
                        memory.addOrAppendEntry("CALLER", txt, true);
                        if (sessionState != null) sessionState.appendTranscript("CALLER", txt);
                    } else {
                        synchronized (pendingOwnerTurn) {
                            if (pendingOwnerTurn.length() > 0) pendingOwnerTurn.append(' ');
                            pendingOwnerTurn.append(txt.trim());
                        }
                        memory.addOrAppendEntry("CONVERSATION", txt, true);
                        if (sessionState != null) sessionState.appendTranscript("CONVERSATION", txt);
                    }
                }
            }

            // Server-driven end-of-turn signal: forward finalized caller utterance to Speaker model
            if (sc.optBoolean("turnComplete", false)) {
                if (isAiTurn) {
                    String finalizedCaller;
                    synchronized (pendingCallerTurn) {
                        finalizedCaller = pendingCallerTurn.toString().trim();
                        pendingCallerTurn.setLength(0);
                    }
                    if (!finalizedCaller.isEmpty()) {
                        forwardFinalTextToSpeaker(finalizedCaller);
                    }
                } else {
                    synchronized (pendingOwnerTurn) {
                        pendingOwnerTurn.setLength(0);
                    }
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error parsing listener JSON", t);
        }
    }

    // ---- 2. Speaker Voice Engine (User's Selected Model e.g. gemini-3.8-live) ----

    private void startOrRestartSpeaker(final String apiKey) {
        stopSpeakerOnly();
        AiCallConfig config = AiCallConfig.getInstance();
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
            CustomLogger.d(TAG, "Forwarded finalized caller turn to Speaker (" + AiCallConfig.getInstance().getModelId() + ")");
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
            CustomLogger.e(TAG, "Error sending listener PCM chunk", t);
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
