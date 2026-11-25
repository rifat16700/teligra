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

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight background speech-to-text WebSocket session active during
 * owner-handled calls so mid-call handover to AI has full conversation context.
 */
public final class BackgroundTranscriptSession {
    private static final String TAG = "BackgroundTranscript";
    private static final String TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live";
    private static final int TARGET_RATE = 16000;
    private static final int BATCH_BYTES_16K = 6400; // ~200ms at 16 kHz mono 16-bit
    private static final int MAX_QUEUE = 120;

    private static final class AudioChunk {
        final byte[] data;
        final int sampleRate;
        final int channels;

        AudioChunk(byte[] data, int sampleRate, int channels) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private final LinkedBlockingQueue<AudioChunk> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private volatile boolean active = false;
    private volatile boolean setupComplete = false;
    private AiCallSessionState sessionState;
    private WebSocket webSocket;
    private Thread workerThread;

    public synchronized void start(AiCallSessionState state) {
        stop();
        if (state == null || !state.isTranscriptBuffering()) return;
        String apiKey = AiCallConfig.getInstance().getApiKey();
        if (apiKey.isEmpty()) {
            CustomLogger.d(TAG, "No API key; background live transcript skipped.");
            return;
        }
        this.sessionState = state;
        this.active = true;
        this.setupComplete = false;
        this.queue.clear();

        workerThread = new Thread(() -> runLoop(apiKey), "BgTranscriptWsThread");
        workerThread.start();
    }

    public void onAudioPcm(byte[] pcm16, int length, int sampleRate, int channels) {
        if (!active || !setupComplete || pcm16 == null || length <= 0) return;
        int safeLen = Math.min(length, pcm16.length);
        byte[] copy = new byte[safeLen];
        System.arraycopy(pcm16, 0, copy, 0, safeLen);
        AudioChunk chunk = new AudioChunk(copy, sampleRate > 0 ? sampleRate : 48000, Math.max(1, channels));
        if (!queue.offer(chunk)) {
            queue.poll();
            queue.offer(chunk);
        }
    }

    public synchronized void stop() {
        active = false;
        setupComplete = false;
        queue.clear();
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
    }

    private void runLoop(String apiKey) {
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
                        setup.put("model", "models/" + TRANSCRIBE_MODEL);
                        setup.put("generationConfig", genConfig);
                        setup.put("inputAudioTranscription", new JSONObject());
                        websocket.sendText(new JSONObject().put("setup", setup).toString());
                    } catch (Throwable t) {
                        CustomLogger.e(TAG, "Failed sending transcript setup", t);
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
                    CustomLogger.e(TAG, "Transcript WS error", cause);
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    setupComplete = false;
                    active = false;
                }
            });

            this.webSocket = ws;
            ws.connect();

            ByteArrayOutputStream batch = new ByteArrayOutputStream(BATCH_BYTES_16K * 2);
            while (active && ws.isOpen()) {
                AudioChunk chunk;
                try {
                    chunk = queue.poll(80, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!active) break;
                    continue;
                }
                if (chunk == null) {
                    if (batch.size() > 0 && setupComplete) {
                        sendAudioBatch(ws, batch.toByteArray());
                        batch.reset();
                    }
                    continue;
                }
                byte[] mono = PcmResampler.toMono(chunk.data, chunk.data.length, chunk.channels);
                byte[] pcm16k = PcmResampler.resampleMono(mono, mono.length, chunk.sampleRate, TARGET_RATE);
                if (pcm16k.length > 0) {
                    batch.write(pcm16k, 0, pcm16k.length);
                    if (batch.size() >= BATCH_BYTES_16K && setupComplete) {
                        sendAudioBatch(ws, batch.toByteArray());
                        batch.reset();
                    }
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Background transcript loop stopped", t);
        } finally {
            active = false;
            setupComplete = false;
        }
    }

    private void sendAudioBatch(WebSocket ws, byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0 || !ws.isOpen()) return;
        try {
            JSONObject audioObj = new JSONObject();
            audioObj.put("mimeType", "audio/pcm;rate=16000");
            audioObj.put("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP));
            JSONObject payload = new JSONObject();
            payload.put("realtimeInput", new JSONObject().put("audio", audioObj));
            ws.sendText(payload.toString());
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error sending transcript audio", t);
        }
    }

    private void handleServerJson(String rawText) {
        if (rawText == null || rawText.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                setupComplete = true;
                CustomLogger.i(TAG, "Background transcript session ready.");
                return;
            }
            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null || sessionState == null) return;

            JSONObject inTrans = sc.optJSONObject("inputTranscription");
            if (inTrans != null) {
                String txt = inTrans.optString("text", "");
                if (!txt.isEmpty()) {
                    sessionState.appendTranscript("Conversation", txt);
                }
            }

            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p != null) {
                            String txt = p.optString("text", "");
                            if (!txt.isEmpty() && !p.optBoolean("thought", false)) {
                                sessionState.appendTranscript("Conversation", txt);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Failed parsing transcript JSON", t);
        }
    }
}
