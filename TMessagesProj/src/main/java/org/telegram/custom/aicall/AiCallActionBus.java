package org.telegram.custom.aicall;

import android.os.Bundle;

import org.telegram.custom.utils.CustomLogger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central background action/command bus for AI call capabilities.
 * Allows UI controls, notification buttons, and background automation
 * to trigger composable call actions independently.
 */
public final class AiCallActionBus {
    private static final String TAG = "AiCallActionBus";

    public static final String ACTION_TAKE_OVER = "org.telegram.custom.aicall.ACTION_TAKE_OVER";
    public static final String ACTION_HANDOVER_TO_AI = "org.telegram.custom.aicall.ACTION_HANDOVER_TO_AI";
    public static final String ACTION_TOGGLE_LISTEN_IN = "org.telegram.custom.aicall.ACTION_TOGGLE_LISTEN_IN";
    public static final String ACTION_TOGGLE_RECORDING = "org.telegram.custom.aicall.ACTION_TOGGLE_RECORDING";
    public static final String ACTION_START_AI_OUTGOING_CALL = "org.telegram.custom.aicall.ACTION_START_AI_OUTGOING_CALL";
    public static final String ACTION_SEND_RECORDING_TO_SAVED_MESSAGES = "org.telegram.custom.aicall.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES";
    public static final String ACTION_OPEN_CHAT_UI = "org.telegram.custom.aicall.ACTION_OPEN_CHAT_UI";
    public static final String ACTION_END_CALL = "org.telegram.custom.aicall.ACTION_END_CALL";

    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_USER_ID = "user_id";

    public interface CommandListener {
        void onAiCallCommand(String action, Bundle extras);
    }

    private static final CopyOnWriteArrayList<CommandListener> listeners = new CopyOnWriteArrayList<>();

    private AiCallActionBus() {}

    public static void registerListener(CommandListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void unregisterListener(CommandListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public static void dispatch(String action, Bundle extras) {
        if (action == null) return;
        CustomLogger.i(TAG, "Dispatch action: " + action);
        for (CommandListener listener : listeners) {
            try {
                listener.onAiCallCommand(action, extras);
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Error in CommandListener for " + action, t);
            }
        }
    }
}
