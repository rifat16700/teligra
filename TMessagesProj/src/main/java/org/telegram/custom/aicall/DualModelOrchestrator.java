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
 * Low-latency Dual-Model Orchestrator:
 * - While AI is active: streams caller 16 kHz PCM DIRECTLY into the user-selected
 *   Voice model (e.g. gemini-3.8-live) with inputAudioTranscription + outputAudioTranscription
 *   enabled, achieving ~1-2s real-time voice response and instant barge-in.
 * - While Owner is speaking (normal call or after Take Over): disconnects the Voice
 *   model and runs gemini-2.5-flash-native-audio-preview-12-2025 in the background to
 *   transcribe and summarize the Owner+Caller conversation so Return to AI has full context.
 */
public final class DualModelOrchestrator implements AiSessionProvider {
    private static final String TAG = "DualModelOrchestrator";
    public static final String LISTENER_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025";
    private static final int GEMINI_INPUT_RATE = 16000;
    private static final int GEMINI_DEFAULT_OUTPUT_RATE = 24000;
    private static final int CHUNK_BATCH_BYTES_16K = 1280; // 40ms at 16 kHz mono 16-bit for low latency
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
    private final LinkedBlockingQueue<PcmChunk> speakerInputQueue = new LinkedBlockingQueue<>(MAX_INPUT_QUEUE);
    private final LinkedBlockingQueue<PcmChunk> listenerInputQueue = new LinkedBlockingQueue<>(MAX_INPUT_QUEUE);

    private volatile boolean active = false;
    private volatile boolean speakerSetupComplete = false;
    private volatile boolean listenerSetupComplete = false;
    private volatile int webrtcOutputSampleRate = 48000;
    private volatile int webrtcOutputChannels = 1;

    private AiCallSessionState sessionState;
    private PcmRingBuffer aiOutputBuffer;
    private String currentOwnerData = "";

    private WebSocket speakerWs;
    private WebSocket listenerWs;
    private Thread speakerThread;
    private Thread listenerThread;

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

        final String apiKey = AiCallConfig.getInstance().getApiKey();
        if (apiKey.isEmpty()) {
            CustomLogger.w(TAG, "Cannot start DualModelOrchestrator: API key is empty.");
            return;
        }

        this.active = true;
        if (state != null && state.isAiActive()) {
            stopListenerOnly();
            startOrRestartSpeaker(apiKey);
        } else {
            stopSpeakerOnly();
            startOrRestartListener(apiKey);
        }
    }

    public void onOwnerLocalAudioPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !listenerSetupComplete || sessionState == null || sessionState.isAiActive()) {
            return;
        }
        enqueueChunk(listenerInputQueue, pcm16, length, sampleRate, channels);
    }

    @Override
    public void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || pcm16 == null || length <= 0) {
            return;
        }
        if (sessionState != null && sessionState.isAiActive()) {
            if (speakerSetupComplete) {
                enqueueChunk(speakerInputQueue, pcm16, length, sampleRate, channels);
            }
        } else if (listenerSetupComplete) {
            enqueueChunk(listenerInputQueue, pcm16, length, sampleRate, channels);
        }
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
        speakerInputQueue.clear();
        if (speakerWs != null) {
            try {
                speakerWs.disconnect();
            } catch (Throwable ignored) {}
            speakerWs = null;
        }
        if (speakerThread != null) {
            speakerThread.interrupt();
            speakerThread = null;
        }
        if (aiOutputBuffer != null) {
            aiOutputBuffer.clear();
        }
    }

    public synchronized void stopListenerOnly() {
        listenerSetupComplete = false;
        listenerInputQueue.clear();
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

    @Override
    public synchronized void stopSession() {
        active = false;
        stopSpeakerOnly();
        stopListenerOnly();
    }

    // ---- 1. Direct Real-Time Speaker Voice WS (e.g. gemini-3.8-live) ----

    private void startOrRestartSpeaker(final String apiKey) {
        stopSpeakerOnly();
        final AiCallConfig config = AiCallConfig.getInstance();
        final String modelId = config.getModelId();
        final String apiVersion = config.getApiVersion();
        final String voiceName = config.getVoiceName();
        final String thinkingLevel = config.getThinkingLevel();

        speakerThread = new Thread(() -> {
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
                streamQueueToWs(ws, speakerInputQueue, true);
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Failed connecting Speaker WS", t);
            } finally {
                speakerSetupComplete = false;
            }
        }, "DualSpeakerWsThread");
        speakerThread.start();
    }

    private void handleSpeakerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                speakerSetupComplete = true;
                CustomLogger.i(TAG, "Direct Speaker WS ready (" + AiCallConfig.getInstance().getModelId() + ")");
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

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    memory.addOrAppendEntry("CALLER", txt, true);
                    if (sessionState != null) sessionState.appendTranscript("CALLER", txt);
                }
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

    // ---- 2. Owner-Mode Background Listener WS (gemini-2.5-flash-native-audio) ----

    private void startOrRestartListener(final String apiKey) {
        stopListenerOnly();
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
                            String sys = "You are a silent background speech transcriber for a phone call.";
                            setup.put("systemInstruction", new JSONObject().put("parts",
                                    new JSONArray().put(new JSONObject().put("text", sys))));
                            websocket.sendText(new JSONObject().put("setup", setup).toString());
                        } catch (Throwable t) {
                            CustomLogger.e(TAG, "Owner-mode listener setup error", t);
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
                        CustomLogger.e(TAG, "Owner-mode listener WS error", cause);
                    }

                    @Override
                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        listenerSetupComplete = false;
                        CustomLogger.i(TAG, "Owner-mode listener WS disconnected");
                    }
                });

                this.listenerWs = ws;
                ws.connect();
                streamQueueToWs(ws, listenerInputQueue, false);
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Owner-mode listener loop error", t);
            } finally {
                listenerSetupComplete = false;
            }
        }, "DualOwnerListenerWsThread");
        listenerThread.start();
    }

    private void handleListenerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                listenerSetupComplete = true;
                CustomLogger.i(TAG, "Owner-mode background listener ready (" + LISTENER_MODEL_ID + ")");
                return;
            }
            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.trim().isEmpty()) {
                    memory.addOrAppendEntry("OWNER", txt, true);
                    if (sessionState != null) sessionState.appendTranscript("OWNER", txt);
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error parsing owner listener JSON", t);
        }
    }

    // ---- Shared streaming loop ----

    private void streamQueueToWs(WebSocket ws, LinkedBlockingQueue<PcmChunk> queue, boolean isSpeaker) {
        ByteArrayOutputStream batch16k = new ByteArrayOutputStream(CHUNK_BATCH_BYTES_16K * 2);
        while (active && ws.isOpen()) {
            boolean ready = isSpeaker ? speakerSetupComplete : listenerSetupComplete;
            PcmChunk chunk;
            try {
                chunk = queue.poll(35, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!active) break;
                continue;
            }
            if (chunk == null) {
                if (batch16k.size() > 0 && ready) {
                    sendPcm16kBatch(ws, batch16k.toByteArray());
                    batch16k.reset();
                }
                continue;
            }
            byte[] mono = PcmResampler.toMono(chunk.data, chunk.data.length, chunk.channels);
            byte[] pcm16k = PcmResampler.resampleMono(mono, mono.length, chunk.sampleRate, GEMINI_INPUT_RATE);
            if (pcm16k.length > 0) {
                batch16k.write(pcm16k, 0, pcm16k.length);
                if (batch16k.size() >= CHUNK_BATCH_BYTES_16K && ready) {
                    sendPcm16kBatch(ws, batch16k.toByteArray());
                    batch16k.reset();
                }
            }
        }
    }

    private String buildSpeakerSetupJson(String modelId, String voiceName, String thinkingLevel) throws Exception {
        boolean isTranscribe = modelId.contains("transcribe");
        String modality = isTranscribe ? "TEXT" : "AUDIO";

        JSONObject generationConfig = new JSONObject();
        generationConfig.put("responseModalities", new JSONArray().put(modality));

        if ("AUDIO".equals(modality)) {
            generationConfig.put("speechConfig", new JSONObject().put("voiceConfig",
                    new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voiceName))));
        }

        if ("LOW".equals(thinkingLevel) || "HIGH".equals(thinkingLevel)) {
            generationConfig.put("thinkingConfig", new JSONObject().put("thinkingLevel", thinkingLevel));
        }

        JSONObject setup = new JSONObject();
        setup.put("model", modelId.startsWith("models/") ? modelId : "models/" + modelId);
        setup.put("generationConfig", generationConfig);
        setup.put("inputAudioTranscription", new JSONObject());
        if ("AUDIO".equals(modality)) {
            setup.put("outputAudioTranscription", new JSONObject());
        }

        if (!isTranscribe) {
            String prompt = memory.buildHandoverContextPrompt(currentOwnerData);
            setup.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", prompt))));

            JSONObject endCallDecl = new JSONObject()
                    .put("name", "end_call")
                    .put("description", "End the phone call politely once the conversation is finished or the caller says goodbye.");
            setup.put("tools", new JSONArray().put(
                    new JSONObject().put("functionDeclarations", new JSONArray().put(endCallDecl))));
        }

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
            CustomLogger.e(TAG, "Error sending PCM chunk", t);
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
