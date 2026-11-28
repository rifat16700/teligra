package org.telegram.custom.aicall;

import android.os.Bundle;
import android.util.Base64;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a complete post-call Bengali Voice + Bengali Text explanation
 * using the user's selected AI model, saves a concise 2-3 line rolling memory
 * for that caller, stores the record locally, and dispatches #nc-call-r backup.
 */
public final class PostCallSummaryGenerator {
    private static final String TAG = "PostCallSummary";
    private static final Pattern RATE_PATTERN = Pattern.compile("rate=(\\d+)", Pattern.CASE_INSENSITIVE);

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
            SummaryResult result = generateWithSelectedAiOrFallback(record, aiPhaseSummaries, ownerPhaseSummaries);
            record.detailedSummary = result.textSummary;
            record.summaryAudioFilePath = result.voiceWavPath;
            AiCallHistoryStore.saveRecord(record);

            // Save concise 2-3 line rolling memory for future calls from this caller
            if (record.peerUserId != 0L && !result.rollingMemory.isEmpty()) {
                CallerProfileStore.getInstance().setRollingMemory(record.peerUserId, result.rollingMemory);
            }

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
                extras.putString(SavedMessagesUploader.EXTRA_DETAILED_SUMMARY, result.textSummary);
                extras.putString(SavedMessagesUploader.EXTRA_TRANSCRIPT, formatMessages(record.messages));
                extras.putLong(SavedMessagesUploader.EXTRA_STARTED_AT, record.startedAtMs);
                extras.putInt(SavedMessagesUploader.EXTRA_HANDOVER_COUNT, record.handoverCount);
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES, extras);
            }
        }, "PostCallSummaryThread").start();
    }

    private static final class SummaryResult {
        final String textSummary;
        final String voiceWavPath;
        final String rollingMemory;

        SummaryResult(String textSummary, String voiceWavPath, String rollingMemory) {
            this.textSummary = textSummary;
            this.voiceWavPath = voiceWavPath;
            this.rollingMemory = rollingMemory;
        }
    }

    private static SummaryResult generateWithSelectedAiOrFallback(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries) {
        AiCallConfig config = AiCallConfig.getInstance();
        String apiKey = config.getApiKey();
        String transcript = formatMessages(record.messages);
        if (apiKey.isEmpty() || transcript.isEmpty()) {
            String fb = buildDeterministicFallback(record, aiPhaseSummaries, ownerPhaseSummaries);
            return new SummaryResult(fb, "", buildShortMemoryFromText(fb));
        }

        final StringBuilder textOut = new StringBuilder();
        final ByteArrayOutputStream audioPcmOut = new ByteArrayOutputStream();
        final int[] outputRate = new int[]{24000};
        final CountDownLatch done = new CountDownLatch(1);
        WebSocket ws = null;

        // Use selected model (or 2.5 Native Audio if selected model is text-only transcribe)
        String selectedModel = config.getModelId();
        if (selectedModel.contains("transcribe")) {
            selectedModel = "gemini-3.8-live";
        }
        final String modelId = selectedModel;
        final String apiVersion = "gemini-3.8-live-extended-thinking".equals(modelId) ? "v1alpha" : config.getApiVersion();
        final String voiceName = config.getVoiceName();

        try {
            String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage."
                    + apiVersion
                    + ".GenerativeService.BidiGenerateContent?key="
                    + URLEncoder.encode(apiKey, "UTF-8");
            ws = new WebSocketFactory().setConnectionTimeout(12000).createSocket(url);

            final String promptText = buildBengaliPrompt(record, aiPhaseSummaries, ownerPhaseSummaries, transcript);
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    try {
                        JSONObject genConfig = new JSONObject();
                        genConfig.put("responseModalities", new JSONArray().put("AUDIO"));
                        genConfig.put("speechConfig", new JSONObject().put("voiceConfig",
                                new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voiceName))));

                        JSONObject setup = new JSONObject();
                        setup.put("model", modelId.startsWith("models/") ? modelId : "models/" + modelId);
                        setup.put("generationConfig", genConfig);
                        setup.put("outputAudioTranscription", new JSONObject());
                        setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(
                                new JSONObject().put("text", "You are the owner's personal call analyst. Explain the entire phone call clearly in natural Bengali (বাংলা) so the owner understands everything in seconds."))));
                        websocket.sendText(new JSONObject().put("setup", setup).toString());
                    } catch (Throwable t) {
                        done.countDown();
                    }
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    handleReply(websocket, text, promptText, textOut, audioPcmOut, outputRate, done);
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary != null && binary.length > 0) {
                        handleReply(websocket, new String(binary, StandardCharsets.UTF_8), promptText, textOut, audioPcmOut, outputRate, done);
                    }
                }

                @Override
                public void onError(WebSocket websocket, com.neovisionaries.ws.client.WebSocketException cause) {
                    done.countDown();
                }
            });

            ws.connect();
            done.await(24, TimeUnit.SECONDS);
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Post-call Bengali summary failed; using fallback", t);
        } finally {
            if (ws != null) {
                try {
                    ws.disconnect();
                } catch (Throwable ignored) {}
            }
        }

        String generated = textOut.toString().trim();
        if (generated.isEmpty()) {
            generated = buildDeterministicFallback(record, aiPhaseSummaries, ownerPhaseSummaries);
        }

        String wavPath = "";
        byte[] pcmBytes = audioPcmOut.toByteArray();
        if (pcmBytes.length > 960) {
            wavPath = writeSummaryWavFile(record.serialId, pcmBytes, outputRate[0]);
        }

        String rollingMem = buildShortMemoryFromText(generated);
        return new SummaryResult(generated, wavPath, rollingMem);
    }

    private static void handleReply(
            WebSocket ws,
            String rawText,
            String promptText,
            StringBuilder textOut,
            ByteArrayOutputStream audioPcmOut,
            int[] outputRate,
            CountDownLatch done) {
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
                if (!txt.isEmpty()) textOut.append(txt);
            }
            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        if (!p.optBoolean("thought", false)) {
                            String txt = p.optString("text", "");
                            if (!txt.isEmpty()) textOut.append(txt);
                        }
                        JSONObject inlineData = p.optJSONObject("inlineData");
                        if (inlineData != null) {
                            String mime = inlineData.optString("mimeType", "");
                            String b64 = inlineData.optString("data", "");
                            if (mime.startsWith("audio/") && !b64.isEmpty()) {
                                Matcher m = RATE_PATTERN.matcher(mime);
                                if (m.find()) {
                                    try {
                                        int r = Integer.parseInt(m.group(1));
                                        if (r > 0) outputRate[0] = r;
                                    } catch (NumberFormatException ignored) {}
                                }
                                byte[] pcm = Base64.decode(b64, Base64.DEFAULT);
                                audioPcmOut.write(pcm, 0, pcm.length);
                            }
                        }
                    }
                }
            }
            if (sc.optBoolean("turnComplete", false)) {
                done.countDown();
            }
        } catch (Throwable ignored) {}
    }

    private static String buildBengaliPrompt(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries,
            String transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("নিচের সম্পূর্ণ ফোন কলের কথোপকথন বিশ্লেষণ করে পুরোপুরি সহজ ও সাবলীল বাংলায় মালিককে বুঝিয়ে বলো (এটিই টেক্সট এবং ভয়েস সামারি হিসেবে সেভ ও শোনানো হবে):\n");
        sb.append("১. মূল বিষয়: কলার কী বিষয়ে কল করেছিল।\n");
        sb.append("২. বিস্তারিত আলোচনা: কে কী বলেছে এবং কী উত্তর দেওয়া হয়েছে (সব হ্যান্ডওভারসহ)।\n");
        sb.append("৩. কোনো মেসেজ বা কাজ: কলার মালিকের জন্য কোনো জরুরি বার্তা বা কাজ রেখে গেছে কি না।\n");
        sb.append("৪. পরবর্তী করণীয়: মালিকের এখন কী করা উচিত।\n\n");
        sb.append("Call ID: ").append(record.serialId).append("\n");
        sb.append("Caller: ").append(record.peerName)
                .append(" | Duration: ").append(record.durationSec).append("s")
                .append(" | Handovers: ").append(record.handoverCount).append("\n\n");
        if (aiPhaseSummaries != null && !aiPhaseSummaries.isEmpty()) {
            sb.append("AI Phase Notes:\n");
            for (String s : aiPhaseSummaries) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        if (ownerPhaseSummaries != null && !ownerPhaseSummaries.isEmpty()) {
            sb.append("Owner Phase Notes:\n");
            for (String s : ownerPhaseSummaries) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }
        sb.append("Full Call Transcript:\n").append(transcript);
        return sb.toString();
    }

    private static String writeSummaryWavFile(String serialId, byte[] pcmBytes, int sampleRate) {
        try {
            File dir = new File(ApplicationLoader.getFilesDirFixed(), "ai_call_recordings");
            if (!dir.exists() && !dir.mkdirs()) return "";
            File outFile = new File(dir, serialId + "_summary.wav");
            int dataLen = pcmBytes.length;
            int totalDataLen = dataLen + 36;
            int byteRate = sampleRate * 2;
            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            header[4] = (byte) (totalDataLen & 0xFF);
            header[5] = (byte) ((totalDataLen >> 8) & 0xFF);
            header[6] = (byte) ((totalDataLen >> 16) & 0xFF);
            header[7] = (byte) ((totalDataLen >> 24) & 0xFF);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            header[16] = 16;
            header[20] = 1; // PCM
            header[22] = 1; // Mono
            header[24] = (byte) (sampleRate & 0xFF);
            header[25] = (byte) ((sampleRate >> 8) & 0xFF);
            header[26] = (byte) ((sampleRate >> 16) & 0xFF);
            header[27] = (byte) ((sampleRate >> 24) & 0xFF);
            header[28] = (byte) (byteRate & 0xFF);
            header[29] = (byte) ((byteRate >> 8) & 0xFF);
            header[30] = (byte) ((byteRate >> 16) & 0xFF);
            header[31] = (byte) ((byteRate >> 24) & 0xFF);
            header[32] = 2;
            header[34] = 16;
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            header[40] = (byte) (dataLen & 0xFF);
            header[41] = (byte) ((dataLen >> 8) & 0xFF);
            header[42] = (byte) ((dataLen >> 16) & 0xFF);
            header[43] = (byte) ((dataLen >> 24) & 0xFF);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(header);
                fos.write(pcmBytes);
            }
            return outFile.getAbsolutePath();
        } catch (IOException e) {
            CustomLogger.e(TAG, "Failed writing summary WAV file", e);
            return "";
        }
    }

    private static String buildShortMemoryFromText(String fullSummary) {
        if (fullSummary == null || fullSummary.trim().isEmpty()) return "";
        String[] lines = fullSummary.trim().split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(t);
            added++;
            if (added >= 3 || sb.length() >= 240) break;
        }
        String res = sb.toString().trim();
        if (res.length() > 280) {
            res = res.substring(0, 277) + "...";
        }
        return res;
    }

    private static String buildDeterministicFallback(
            AiCallHistoryStore.CallRecord record,
            List<String> aiPhaseSummaries,
            List<String> ownerPhaseSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("কলার: ").append(record.peerName)
                .append(" | সময়কাল: ").append(record.durationSec).append(" সেকেন্ড")
                .append(" | হ্যান্ডওভার: ").append(record.handoverCount).append("\n");
        if (aiPhaseSummaries != null && !aiPhaseSummaries.isEmpty()) {
            sb.append("\nAI অংশ:\n");
            for (String s : aiPhaseSummaries) sb.append("• ").append(s).append("\n");
        }
        if (ownerPhaseSummaries != null && !ownerPhaseSummaries.isEmpty()) {
            sb.append("\nমালিকের অংশ:\n");
            for (String s : ownerPhaseSummaries) sb.append("• ").append(s).append("\n");
        }
        if (record.messages != null && !record.messages.isEmpty()) {
            sb.append("\nমোট রেকর্ডকৃত মেসেজ: ").append(record.messages.size());
        } else {
            sb.append("\nএই কলের কোনো স্পিচ ট্রান্সক্রিপ্ট পাওয়া যায়নি।");
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
