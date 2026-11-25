package org.telegram.custom.aicall;

import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.io.File;

/**
 * Listens for ACTION_SEND_RECORDING_TO_SAVED_MESSAGES and uploads the
 * finalized WAV call recording to the owner's Saved Messages chat.
 */
public final class SavedMessagesUploader implements AiCallActionBus.CommandListener {
    private static final String TAG = "SavedMessagesUploader";

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_DURATION_SEC = "duration_sec";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TRANSCRIPT = "transcript";

    @Override
    public void onAiCallCommand(String action, Bundle extras) {
        if (!AiCallActionBus.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES.equals(action) || extras == null) {
            return;
        }
        String filePath = extras.getString(EXTRA_FILE_PATH);
        if (TextUtils.isEmpty(filePath)) {
            CustomLogger.w(TAG, "No recording file_path in bundle; skipping upload.");
            return;
        }
        File file = new File(filePath);
        if (!file.exists() || file.length() <= 44L) {
            CustomLogger.w(TAG, "Recording file missing or empty: " + filePath);
            return;
        }

        int account = extras.getInt(AiCallActionBus.EXTRA_ACCOUNT, UserConfig.selectedAccount);
        long durationSec = extras.getLong(EXTRA_DURATION_SEC, 0L);
        String mode = extras.getString(EXTRA_MODE, "CALL");
        String transcript = extras.getString(EXTRA_TRANSCRIPT, "");

        long savedDialogId = UserConfig.getInstance(account).getClientUserId();
        if (savedDialogId == 0L) {
            CustomLogger.w(TAG, "Client user ID is 0 for account=" + account + "; cannot send to Saved Messages.");
            return;
        }

        String caption = "Call Recording (" + durationSec + "s) · " + mode;
        if (!TextUtils.isEmpty(transcript)) {
            String trimmed = transcript.trim();
            if (trimmed.length() > 600) {
                trimmed = trimmed.substring(0, 597) + "...";
            }
            caption += "\n\nTranscript:\n" + trimmed;
        }
        final String finalCaption = caption;
        CustomLogger.i(TAG, "Uploading recording to Saved Messages: " + filePath + " | dialogId=" + savedDialogId);

        AndroidUtilities.runOnUIThread(() -> {
            try {
                SendMessagesHelper.prepareSendingDocument(
                        AccountInstance.getInstance(account),
                        filePath,
                        filePath,
                        null,
                        finalCaption,
                        "audio/wav",
                        savedDialogId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        0,
                        null,
                        null,
                        false
                );
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Failed to send call recording to Saved Messages", t);
            }
        });
    }
}
