package org.telegram.custom.aicall;

import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Uploads #nc-call-r call artifacts (WAV audio, detailed AI summary, and
 * chunked chat transcript) to Saved Messages under a unified Call Serial ID.
 * Messages starting with #nc-call-r are hidden from Saved Messages UI and
 * displayed cleanly inside AiCallHistoryActivity.
 */
public final class SavedMessagesUploader implements AiCallActionBus.CommandListener {
    private static final String TAG = "SavedMessagesUploader";
    private static final int MAX_TEXT_CHUNK = 3400;

    public static final String EXTRA_SERIAL_ID = "serial_id";
    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_DURATION_SEC = "duration_sec";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_DETAILED_SUMMARY = "detailed_summary";
    public static final String EXTRA_TRANSCRIPT = "transcript";
    public static final String EXTRA_STARTED_AT = "started_at";
    public static final String EXTRA_HANDOVER_COUNT = "handover_count";

    @Override
    public void onAiCallCommand(String action, Bundle extras) {
        if (!AiCallActionBus.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES.equals(action) || extras == null) {
            return;
        }

        final int account = extras.getInt(AiCallActionBus.EXTRA_ACCOUNT, UserConfig.selectedAccount);
        final long savedDialogId = UserConfig.getInstance(account).getClientUserId();
        if (savedDialogId == 0L) {
            CustomLogger.w(TAG, "Client user ID is 0 for account=" + account + "; skipping Saved Messages upload.");
            return;
        }

        final String serialId = extras.getString(EXTRA_SERIAL_ID, CallContextMemory.generateSerialId(0L, 0L));
        final String peerName = extras.getString(EXTRA_PEER_NAME, "Caller");
        final String filePath = extras.getString(EXTRA_FILE_PATH, "");
        final long durationSec = extras.getLong(EXTRA_DURATION_SEC, 0L);
        final String mode = extras.getString(EXTRA_MODE, "INCOMING");
        final String detailedSummary = extras.getString(EXTRA_DETAILED_SUMMARY, "");
        final String transcript = extras.getString(EXTRA_TRANSCRIPT, "");
        final long startedAt = extras.getLong(EXTRA_STARTED_AT, System.currentTimeMillis());
        final int handovers = extras.getInt(EXTRA_HANDOVER_COUNT, 0);

        final String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(startedAt));
        final String headerPrefix = CallContextMemory.HIDDEN_TAG
                + " | ID: " + serialId
                + " | Time: " + timeStr
                + " | Caller: " + peerName
                + " | Dir: " + mode
                + " | Dur: " + durationSec + "s"
                + " | Handovers: " + handovers;

        AndroidUtilities.runOnUIThread(() -> {
            // 1. Upload WAV recording if present
            if (!TextUtils.isEmpty(filePath)) {
                File audioFile = new File(filePath);
                if (audioFile.exists() && audioFile.length() > 44L) {
                    try {
                        final String audioCaption = headerPrefix + " | PART: AUDIO";
                        SendMessagesHelper.prepareSendingDocument(
                                AccountInstance.getInstance(account),
                                filePath,
                                filePath,
                                null,
                                audioCaption,
                                "audio/wav",
                                savedDialogId,
                                null, null, null, null, null,
                                true, 0, null, null, false
                        );
                    } catch (Throwable t) {
                        CustomLogger.e(TAG, "Failed uploading WAV recording", t);
                    }
                }
            }

            // 2. Upload detailed post-call AI summary
            if (!TextUtils.isEmpty(detailedSummary)) {
                sendTextPart(account, savedDialogId, headerPrefix + " | PART: SUMMARY\n\n" + detailedSummary.trim());
            }

            // 3. Upload full conversation transcript in numbered serial chunks
            if (!TextUtils.isEmpty(transcript)) {
                List<String> chunks = splitChunks(transcript.trim(), MAX_TEXT_CHUNK);
                int total = chunks.size();
                for (int i = 0; i < total; i++) {
                    String partMsg = headerPrefix + " | PART: TRANSCRIPT " + (i + 1) + "/" + total + "\n\n" + chunks.get(i);
                    sendTextPart(account, savedDialogId, partMsg);
                }
            }
        });
    }

    private static void sendTextPart(int account, long savedDialogId, String text) {
        try {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    text,
                    savedDialogId,
                    null, null, null,
                    false,
                    null, null, null,
                    true,
                    0, 0,
                    null,
                    false
            );
            SendMessagesHelper.getInstance(account).sendMessage(params);
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Failed sending Saved Messages text part", t);
        }
    }

    private static List<String> splitChunks(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        int len = text.length();
        int idx = 0;
        while (idx < len) {
            int end = Math.min(len, idx + maxLen);
            if (end < len) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > idx + (maxLen / 2)) {
                    end = nl + 1;
                }
            }
            out.add(text.substring(idx, end));
            idx = end;
        }
        return out;
    }
}
