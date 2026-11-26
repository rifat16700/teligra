package org.telegram.custom.aicall;

import android.os.Bundle;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.custom.utils.CustomLogger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Generates a comprehensive post-call report combining all handover phases,
 * saves the record to AiCallHistoryStore, and dispatches #nc-call-r uploads
 * to Saved Messages.
 */
public final class PostCallSummaryGenerator {
    private static final String TAG = "PostCallSummary";

    private PostCallSummaryGenerator() {}

    public static void finalizeAndDispatch(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries,
            int account,
            long callId,
            boolean uploadToSavedMessages) {
        if (record == null) return;

        new Thread(() -> {
            String summary = generateWithGeminiOrFallback(record, aiPhaseSummaries, ownerPhaseSummaries);
            record.detailedSummary = summary;
            AiCallHistoryStore.saveRecord(record);

            if (uploadToSavedMessages) {
                Bundle extras = new Bundle();
                extras.putLong(AiCallActionBus.EXTRA_CALL_ID, callId);
                extras.putInt(AiCallActionBus.EXTRA_ACCOUNT, account);
                extras.putLong(AiCallActionBus.EXTRA_USER_ID, record.peerUserId);
                extras.putString(SavedMessagesUploader.EXTRA_SERIAL_ID, record.serialId);
                extras.putString(SavedMessagesUploader.EXTRA_PEER_NAME, record.peerName);
                extras.putString(SavedMessagesUploader.EXTRA_FILE_PATH, record.audioFilePath);
                extras.putLong(SavedMessagesUploader.EXTRA_DURATION_SEC, record.durationSec);
                extras.putString(SavedMessagesUploader.EXTRA_MODE, record.direction);
                extras.putString(SavedMessagesUploader.EXTRA_DETAILED_SUMMARY, summary);
                extras.putString(SavedMessagesUploader.EXTRA_TRANSCRIPT, formatMessages(record.messages));
                extras.putLong(SavedMessagesUploader.EXTRA_STARTED_AT, record.startedAtMs);
                extras.putInt(SavedMessagesUploader.EXTRA_HANDOVER_COUNT, record.handoverCount);
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES, extras);
            }
        }, "PostCallSummaryThread").start();
    }

    private static String generateWithGeminiOrFallback(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries) {
        String apiKey = AiCallConfig.getInstance().getApiKey();
        String transcript = formatMessages(record.messages);
        if (apiKey.isEmpty() || transcript.isEmpty()) {
            return buildDeterministicFallback(record, aiPhaseSummaries, ownerPhaseSummaries);
        }

        final StringBuilder result = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        WebSocket ws = null;

        try {
            String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
                    + URLEncoder.encode(apiKey, "UTF-8");
            ws = new WebSocketFactory().setConnectionTimeout(10000).createSocket(url);

            final String promptText = buildPrompt(record, aiPhaseSummaries, ownerPhaseSummaries, transcript);
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    try {
                        JSONObject genConfig = new JSONObject();
                        genConfig.put("responseModalities", new JSONArray().put("AUDIO"));
                        JSONObject setup = new JSONObject();
                        setup.put("model", "models/" + DualModelOrchestrator.LISTENER_MODEL_ID);
                        setup.put("generationConfig", genConfig);
                        setup.put("outputAudioTranscription", new JSONObject());
                        websocket.sendText(new JSONObject().put("setup", setup).toString());
                    } catch (Throwable t) {
                        done.countDown();
                    }
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    handleReply(websocket, text, promptText, result, done);
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary != null && binary.length > 0) {
                        handleReply(websocket, new String(binary, StandardCharsets.UTF_8), promptText, result, done);
                    }
                }

                @Override
                public void onError(WebSocket websocket, com.neovisionaries.ws.client.WebSocketException cause) {
                    done.countDown();
                }
            });

            ws.connect();
            done.await(18, TimeUnit.SECONDS);
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Post-call Gemini summary failed; using fallback", t);
        } finally {
            if (ws != null) {
                try {
                    ws.disconnect();
                } catch (Throwable ignored) {}
            }
        }

        String generated = result.toString().trim();
        return generated.isEmpty() ? buildDeterministicFallback(record, aiPhaseSummaries, ownerPhaseSummaries) : generated;
    }

    private static void handleReply(WebSocket ws, String rawText, String promptText, StringBuilder result, CountDownLatch done) {
        try {
            JSONObject data = new JSONObject(rawText);
            if (data.has("setupComplete")) {
                JSONObject turn = new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", promptText)));
                JSONObject clientContent = new JSONObject()
                        .put("turns", new JSONArray().put(turn))
                        .put("turnComplete", true);
                ws.sendText(new JSONObject().put("clientContent", clientContent).toString());
                return;
            }
            JSONObject sc = data.optJSONObject("serverContent");
            if (sc == null) return;

            JSONObject outTrans = sc.optJSONObject("outputTranscription");
            if (outTrans != null) {
                String txt = outTrans.optString("text", "");
                if (!txt.isEmpty()) result.append(txt);
            }
            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p != null && !p.optBoolean("thought", false)) {
                            String txt = p.optString("text", "");
                            if (!txt.isEmpty()) result.append(txt);
                        }
                    }
                }
            }
            if (sc.optBoolean("turnComplete", false)) {
                done.countDown();
            }
        } catch (Throwable ignored) {}
    }

    private static String buildPrompt(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries,
            String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("Create a clear, structured final call report from this call record. ");
        sb.append("Include:\n1. Main Topic\n2. Detailed Explanation (who said what across handovers)\n3. Caller Message / Action Items Left for Owner\n4. Final Outcome\n\n");
        sb.append("Call ID: ").append(record.serialId).append("\n");
        sb.append("Caller: ").append(record.peerName).append(" | Duration: ").append(record.durationSec).append("s | Handovers: ").append(record.handoverCount).append("\n\n");
        if (aiPhaseSummaries != null && !aiPhaseSummaries.isEmpty()) {
            sb.append("AI Phase Summaries:\n");
            for (String s : aiPhaseSummaries) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        if (ownerPhaseSummaries != null && !ownerPhaseSummaries.isEmpty()) {
            sb.append("Owner Phase Summaries:\n");
            for (String s : ownerPhaseSummaries) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        sb.append("Full Transcript:\n").append(transcript);
        return sb.toString();
    }

    private static String buildDeterministicFallback(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Caller: ").append(record.peerName)
                .append(" | Duration: ").append(record.durationSec).append("s")
                .append(" | Handovers: ").append(record.handoverCount).append("\n");
        if (aiPhaseSummaries != null && !aiPhaseSummaries.isEmpty()) {
            sb.append("\nAI Handling Phases:\n");
            for (String s : aiPhaseSummaries) sb.append("• ").append(s).append("\n");
        }
        if (ownerPhaseSummaries != null && !ownerPhaseSummaries.isEmpty()) {
            sb.append("\nOwner Handling Phases:\n");
            for (String s : ownerPhaseSummaries) sb.append("• ").append(s).append("\n");
        }
        if (record.messages != null && !record.messages.isEmpty()) {
            sb.append("\nTotal captured messages: ").append(record.messages.size());
        } else {
            sb.append("\nNo speech transcript was captured for this call.");
        }
        return sb.toString().trim();
    }

    private static String formatMessages(List<CallContextMemory.ChatEntry> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CallContextMemory.ChatEntry e : messages) {
            sb.append("[").append(e.role).append("]: ").append(e.text).append("\n");
        }
        return sb.toString().trim();
    }
}
