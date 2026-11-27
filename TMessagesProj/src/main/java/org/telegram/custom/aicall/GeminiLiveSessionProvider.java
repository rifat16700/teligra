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
 * Pure WebSocket (wss://) Gemini Live API session provider.
 * Uses the user's own locally saved Google AI Studio API key (BYOK),
 * streams 16 kHz PCM caller audio up, and plays resampled Gemini PCM audio down.
 */
public final class GeminiLiveSessionProvider implements AiSessionProvider {
    private static final String TAG = "GeminiLiveSession";
    private static final int GEMINI_INPUT_RATE = 16000;
    private static final int GEMINI_DEFAULT_OUTPUT_RATE = 24000;
    private static final int CHUNK_BATCH_BYTES_16K = 1280; // 40ms at 16 kHz mono 16-bit
    private static final int NOISE_GATE_RMS = 180;
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

    private final LinkedBlockingQueue<PcmChunk> inputQueue = new LinkedBlockingQueue<>(MAX_INPUT_QUEUE);

    private volatile boolean active = false;
    private volatile boolean setupComplete = false;
    private volatile int webrtcOutputSampleRate = 48000;
    private volatile int webrtcOutputChannels = 1;

    private AiCallSessionState sessionState;
    private PcmRingBuffer aiOutputBuffer;
    private WebSocket webSocket;
    private Thread workerThread;

    public void setTargetOutputSampleRate(int sampleRate) {
        if (sampleRate > 0) {
            this.webrtcOutputSampleRate = sampleRate;
        }
    }

    public void setTargetOutputChannels(int channels) {
        if (channels > 0) {
            this.webrtcOutputChannels = channels;
        }
    }

    @Override
    public synchronized void startSession(AiCallSessionState state, String ownerData, PcmRingBuffer aiOutputBuffer) {
        stopSession();
        this.sessionState = state;
        this.aiOutputBuffer = aiOutputBuffer;

        AiCallConfig config = AiCallConfig.getInstance();
        String apiKey = config.getApiKey();
        if (apiKey.isEmpty()) {
            CustomLogger.w(TAG, "Cannot start Gemini Live WebSocket: API key is empty.");
            return;
        }

        String apiVersion = config.getApiVersion();
        String modelId = config.getModelId();
        String voiceName = config.getVoiceName();
        String thinkingLevel = config.getThinkingLevel();

        active = true;
        setupComplete = false;
        inputQueue.clear();

        workerThread = new Thread(() -> runSession(apiKey, apiVersion, modelId, voiceName, thinkingLevel, ownerData, state), "GeminiLiveWsThread");
        workerThread.start();
    }

    @Override
    public void onCallerPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !setupComplete || pcm16 == null || length <= 0) {
            return;
        }
        int safeLen = Math.min(length, pcm16.length);
        byte[] copy = new byte[safeLen];
        System.arraycopy(pcm16, 0, copy, 0, safeLen);
        PcmChunk chunk = new PcmChunk(copy, sampleRate > 0 ? sampleRate : 48000, Math.max(1, channels));
        if (!inputQueue.offer(chunk)) {
            inputQueue.poll();
            inputQueue.offer(chunk);
        }
    }

    @Override
    public void onBargeIn() {
        if (aiOutputBuffer != null) {
            aiOutputBuffer.clear();
        }
    }

    @Override
    public synchronized void stopSession() {
        active = false;
        setupComplete = false;
        inputQueue.clear();
        if (webSocket != null) {
            try {
                webSocket.disconnect();
            } catch (Throwable ignored) {}
            webSocket = null;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (aiOutputBuffer != null) {
            aiOutputBuffer.clear();
        }
    }

    private void runSession(String apiKey, String apiVersion, String modelId, String voiceName, String thinkingLevel, String ownerData, AiCallSessionState state) {
        try {
            String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage."
                    + apiVersion
                    + ".GenerativeService.BidiGenerateContent?key="
                    + URLEncoder.encode(apiKey, "UTF-8");

            CustomLogger.i(TAG, "Connecting Gemini Live WS | model=" + modelId + " | ver=" + apiVersion + " | voice=" + voiceName);

            WebSocketFactory factory = new WebSocketFactory().setConnectionTimeout(12000);
            WebSocket ws = factory.createSocket(url);
            ws.setPingInterval(25000);

            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    CustomLogger.i(TAG, "WebSocket connected; sending setup payload.");
                    try {
                        String setupJson = buildSetupJson(modelId, voiceName, thinkingLevel, ownerData, state);
                        websocket.sendText(setupJson);
                    } catch (Throwable t) {
                        CustomLogger.e(TAG, "Failed sending setup JSON", t);
                    }
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    handleServerJson(text);
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary != null && binary.length > 0) {
                        handleServerJson(new String(binary, StandardCharsets.UTF_8));
                    }
                }

                @Override
                public void onError(WebSocket websocket, WebSocketException cause) {
                    CustomLogger.e(TAG, "WebSocket error", cause);
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    String reason = serverCloseFrame != null ? serverCloseFrame.getCloseReason() : "";
                    int code = serverCloseFrame != null ? serverCloseFrame.getCloseCode() : -1;
                    CustomLogger.i(TAG, "WebSocket closed | byServer=" + closedByServer + " code=" + code + " reason=" + reason);
                    setupComplete = false;
                    active = false;
                }
            });

            this.webSocket = ws;
            ws.connect();

            ByteArrayOutputStream batch16k = new ByteArrayOutputStream(CHUNK_BATCH_BYTES_16K * 2);
            while (active && ws.isOpen()) {
                PcmChunk chunk;
                try {
                    chunk = inputQueue.poll(60, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!active) break;
                    continue;
                }
                if (chunk == null) {
                    if (batch16k.size() > 0 && setupComplete) {
                        byte[] out = batch16k.toByteArray();
                        PcmResampler.applyNoiseGateInPlace(out, out.length, NOISE_GATE_RMS);
                        sendPcm16kBatch(ws, out);
                        batch16k.reset();
                    }
                    continue;
                }
                byte[] mono = PcmResampler.toMono(chunk.data, chunk.data.length, chunk.channels);
                byte[] pcm16k = PcmResampler.resampleMono(mono, mono.length, chunk.sampleRate, GEMINI_INPUT_RATE);
                if (pcm16k.length > 0) {
                    batch16k.write(pcm16k, 0, pcm16k.length);
                    if (batch16k.size() >= CHUNK_BATCH_BYTES_16K && setupComplete) {
                        byte[] out = batch16k.toByteArray();
                        PcmResampler.applyNoiseGateInPlace(out, out.length, NOISE_GATE_RMS);
                        sendPcm16kBatch(ws, out);
                        batch16k.reset();
                    }
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Gemini Live session loop terminated with error", t);
        } finally {
            active = false;
            setupComplete = false;
        }
    }

    private void sendPcm16kBatch(WebSocket ws, byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0 || ws == null || !ws.isOpen()) return;
        try {
            String b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP);
            JSONObject audioObj = new JSONObject();
            audioObj.put("mimeType", "audio/pcm;rate=16000");
            audioObj.put("data", b64);

            JSONObject realtimeInput = new JSONObject();
            realtimeInput.put("audio", audioObj);

            JSONObject payload = new JSONObject();
            payload.put("realtimeInput", realtimeInput);
            ws.sendText(payload.toString());
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Failed sending audio chunk", t);
        }
    }

    private void handleServerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                setupComplete = true;
                CustomLogger.i(TAG, "Gemini Live setupComplete received; audio bridge ready.");
                return;
            }

            JSONObject toolCall = data.optJSONObject("toolCall");
            if (toolCall != null) {
                JSONArray fnCalls = toolCall.optJSONArray("functionCalls");
                if (fnCalls != null) {
                    for (int i = 0; i < fnCalls.length(); i++) {
                        JSONObject fn = fnCalls.optJSONObject(i);
                        if (fn != null && "end_call".equals(fn.optString("name"))) {
                            CustomLogger.i(TAG, "Gemini requested end_call; scheduling hang-up after goodbye.");
                            AndroidUtilities.runOnUIThread(() -> AiCallActionBus.dispatch(AiCallActionBus.ACTION_END_CALL, null), 1800L);
                        }
                    }
                }
            }

            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            if (sc.optBoolean("interrupted", false)) {
                onBargeIn();
                CustomLogger.d(TAG, "Gemini Live interrupted (barge-in); cleared AI output buffer.");
            }

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null && sessionState != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.isEmpty()) {
                    sessionState.appendTranscript("Caller", txt);
                }
            }

            JSONObject outTrans = sc.optJSONObject("outputTranscription");
            if (outTrans != null && sessionState != null) {
                String txt = outTrans.optString("text", "");
                if (!txt.isEmpty()) {
                    sessionState.appendTranscript("AI", txt);
                }
            }

            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
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
            CustomLogger.e(TAG, "Error parsing Gemini server JSON", t);
        }
    }

    private static String buildSetupJson(String modelId, String voiceName, String thinkingLevel, String ownerData, AiCallSessionState state) throws Exception {
        boolean isTranscribe = modelId.contains("transcribe");
        String modality = isTranscribe ? "TEXT" : "AUDIO";

        JSONObject generationConfig = new JSONObject();
        generationConfig.put("responseModalities", new JSONArray().put(modality));

        if ("AUDIO".equals(modality)) {
            JSONObject prebuiltVoice = new JSONObject();
            prebuiltVoice.put("voiceName", voiceName);
            JSONObject voiceConfig = new JSONObject();
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoice);
            JSONObject speechConfig = new JSONObject();
            speechConfig.put("voiceConfig", voiceConfig);
            generationConfig.put("speechConfig", speechConfig);
        }

        if ("LOW".equals(thinkingLevel) || "HIGH".equals(thinkingLevel)) {
            JSONObject thinkingConfig = new JSONObject();
            thinkingConfig.put("thinkingLevel", thinkingLevel);
            generationConfig.put("thinkingConfig", thinkingConfig);
        }

        JSONObject setup = new JSONObject();
        setup.put("model", modelId.startsWith("models/") ? modelId : "models/" + modelId);
        setup.put("generationConfig", generationConfig);
        if (modelId.contains("gemini-2.5")) {
            setup.put("enableAffectiveDialog", true);
        }

        if (!isTranscribe) {
            AiCallConfig config = AiCallConfig.getInstance();
            String profileInstruction = config.getEffectiveProfileInstruction();
            String emotionInstruction = config.getEffectiveEmotionInstruction();

            StringBuilder sys = new StringBuilder();
            sys.append("You are answering a live Telegram voice call on behalf of the owner. ");
            sys.append("Speak naturally, politely, and concisely. Answer strictly based on the owner's saved information below.\n");
            if (!profileInstruction.isEmpty()) {
                sys.append("\n[Role & Use-Case Profile: ").append(config.getProfileLabel()).append("]\n")
                        .append(profileInstruction).append("\n");
            }
            if (!emotionInstruction.isEmpty()) {
                sys.append("\n[Voice Emotion & Speaking Style]\n")
                        .append(emotionInstruction).append("\n");
            }
            if (ownerData != null && !ownerData.trim().isEmpty()) {
                sys.append("\n[Owner Data]\n").append(ownerData.trim()).append("\n");
            } else {
                sys.append("\n[Owner Data]\nThe owner is currently unavailable. Offer to take a message.\n");
            }
            if (state != null) {
                String priorTranscript = state.getFormattedTranscript();
                if (!priorTranscript.isEmpty()) {
                    sys.append("\n[Conversation Transcript Before Handover]\n").append(priorTranscript).append("\n");
                }
            }
            JSONObject sysPart = new JSONObject().put("text", sys.toString());
            setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(sysPart)));

            JSONObject endCallDecl = new JSONObject()
                    .put("name", "end_call")
                    .put("description", "End the phone call politely once the conversation is finished or the caller says goodbye.");
            JSONObject toolEntry = new JSONObject().put("functionDeclarations", new JSONArray().put(endCallDecl));
            setup.put("tools", new JSONArray().put(toolEntry));
        }

        setup.put("inputAudioTranscription", new JSONObject());
        if ("AUDIO".equals(modality)) {
            setup.put("outputAudioTranscription", new JSONObject());
        }
        setup.put("realtimeInputConfig", DualModelOrchestrator.buildFastVadConfig());

        return new JSONObject().put("setup", setup).toString();
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
