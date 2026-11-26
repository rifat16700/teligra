package org.telegram.custom.aicall;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.voip.VoIPActionsReceiver;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SettingsActivity;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Minimal hook entry points called from upstream Telegram files.
 */
public final class AiCallHooks {
    public static final int SETTINGS_ITEM_AI_CALL_ID = 9001;

    private AiCallHooks() {}

    public static void onSettingsFillItems(ArrayList<UItem> items) {
        if (items == null) return;
        AiCallConfig config = AiCallConfig.getInstance();
        String subtitle = config.isAutoAnswerEnabled()
                ? "On · " + config.getRingDelaySeconds() + "s"
                : "Off";
        items.add(SettingsActivity.SettingCell.Factory.of(
                SETTINGS_ITEM_AI_CALL_ID,
                0xFF5B6DF6,
                0xFF3E4CE0,
                R.drawable.call,
                "AI Auto-Answer Call",
                subtitle
        ));
    }

    public static boolean onSettingsItemClick(BaseFragment fragment, UItem item) {
        if (fragment == null || item == null) return false;
        if (item.id == SETTINGS_ITEM_AI_CALL_ID) {
            fragment.presentFragment(new AiCallSettingsActivity());
            return true;
        }
        return false;
    }

    public static void onPreNotificationShow(Context context, int account, long userId, long callId, boolean video) {
        AiCallController.getInstance().onPreNotificationIncoming(context, account, userId, callId, video);
    }

    public static void onPreNotificationDismiss(boolean answered) {
        AiCallController.getInstance().onPreNotificationDismissed(answered);
    }

    public static void onVoipStartRinging(VoIPService service) {
        AiCallController.getInstance().onVoipServiceRinging(service);
    }

    public static void onVoipAcceptIncomingCall(VoIPService service) {
        AiCallController.getInstance().onVoipServiceCallAccepted(service);
    }

    public static void onVoipDestroy(VoIPService service) {
        AiCallController.getInstance().onVoipServiceDestroyed(service);
    }

    public static void onVoipStartOutgoingCall(VoIPService service) {
        AiCallController.getInstance().onVoipServiceOutgoingCall(service);
    }

    public static void onBuildCallNotification(VoIPService service, Notification.Builder builder) {
        if (service == null || builder == null) return;
        AiCallSessionState state = AiCallController.getInstance().getState();
        if (state.getMode() == AiCallSessionState.Mode.IDLE) return;

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        if (state.isAiActive()) {
            builder.setContentTitle(state.isAnonymousListenIn() ? "AI Call (Listening Live)" : "AI Call Active (Silent)");
            PendingIntent takeOverPi = PendingIntent.getBroadcast(
                    service, 9101, new Intent(service, VoIPActionsReceiver.class).setAction(AiCallActionBus.ACTION_TAKE_OVER), flags);
            builder.addAction(R.drawable.call, "Take Over", takeOverPi);

            PendingIntent listenPi = PendingIntent.getBroadcast(
                    service, 9102, new Intent(service, VoIPActionsReceiver.class).setAction(AiCallActionBus.ACTION_TOGGLE_LISTEN_IN), flags);
            builder.addAction(R.drawable.call, state.isAnonymousListenIn() ? "Mute Listen" : "Listen Live", listenPi);
        } else {
            PendingIntent handoverPi = PendingIntent.getBroadcast(
                    service, 9103, new Intent(service, VoIPActionsReceiver.class).setAction(AiCallActionBus.ACTION_HANDOVER_TO_AI), flags);
            builder.addAction(R.drawable.call, "Handover to AI", handoverPi);

            PendingIntent recPi = PendingIntent.getBroadcast(
                    service, 9104, new Intent(service, VoIPActionsReceiver.class).setAction(AiCallActionBus.ACTION_TOGGLE_RECORDING), flags);
            builder.addAction(R.drawable.call, state.isRecordingActive() ? "Rec: ON" : "Rec: OFF", recPi);
        }

        PendingIntent chatPi = PendingIntent.getBroadcast(
                service, 9105, new Intent(service, VoIPActionsReceiver.class).setAction(AiCallActionBus.ACTION_OPEN_CHAT_UI), flags);
        builder.addAction(R.drawable.call, "Open AI Chat", chatPi);
    }

    public static boolean onNotificationAction(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return false;
        String action = intent.getAction();
        if (AiCallActionBus.ACTION_OPEN_CHAT_UI.equals(action)) {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (LaunchActivity.instance != null) {
                    LaunchActivity.instance.presentFragment(new AiCallChatFragment());
                }
            });
            return true;
        }
        if (AiCallActionBus.ACTION_TAKE_OVER.equals(action)
                || AiCallActionBus.ACTION_HANDOVER_TO_AI.equals(action)
                || AiCallActionBus.ACTION_TOGGLE_LISTEN_IN.equals(action)
                || AiCallActionBus.ACTION_TOGGLE_RECORDING.equals(action)) {
            AiCallActionBus.dispatch(action, intent.getExtras());
            return true;
        }
        return false;
    }

    public static boolean shouldHideSavedCallRecord(long dialogId, long selfUserId, MessageObject obj) {
        if (selfUserId == 0L || dialogId != selfUserId || obj == null) {
            return false;
        }
        if (startsWithHiddenTag(obj.messageText) || startsWithHiddenTag(obj.caption)) {
            return true;
        }
        if (obj.messageOwner != null && startsWithHiddenTag(obj.messageOwner.message)) {
            return true;
        }
        return false;
    }

    private static boolean startsWithHiddenTag(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        return s.startsWith(CallContextMemory.HIDDEN_TAG);
    }

    public static boolean shouldForceJavaAudio() {
        return AiCallController.getInstance().shouldForceJavaAudio();
    }

    public static void onWebRtcPlayout(ByteBuffer byteBuffer, int sizeInBytes, int sampleRate, int channels) {
        AiAudioBridge.getInstance().onWebRtcPlayout(byteBuffer, sizeInBytes, sampleRate, channels);
    }

    public static void onWebRtcRecord(ByteBuffer byteBuffer, int sizeInBytes, int sampleRate, int channels) {
        AiAudioBridge.getInstance().onWebRtcRecord(byteBuffer, sizeInBytes, sampleRate, channels);
    }
}
