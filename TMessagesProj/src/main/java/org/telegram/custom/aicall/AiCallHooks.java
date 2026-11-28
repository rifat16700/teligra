package org.telegram.custom.aicall;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPActionsReceiver;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.voip.VoIPHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SettingsActivity;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Minimal hook entry points called from upstream Telegram files.
 */
public final class AiCallHooks {
    public static final int SETTINGS_ITEM_AI_CALL_ID = 9001;
    public static final int SAVED_MESSAGES_ARCHIVE_ITEM_ID = 9009;
    public static final int CHAT_MENU_AI_CALL_ID = 9010;
    public static final int CHAT_MENU_CALLER_DATA_ID = 9011;

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

    public static void onVoipFragmentAttached(Context context, ViewGroup windowView) {
        if (context == null || windowView == null) return;
        windowView.addView(new AiCallOverlayView(context));
    }

    public static void onChatMenuCreated(long dialogId, long selfUserId, TLRPC.User currentUser, ActionBarMenu menu, ActionBarMenuItem headerItem) {
        if (selfUserId != 0L && dialogId == selfUserId && menu != null) {
            ActionBarMenuItem item = menu.addItem(SAVED_MESSAGES_ARCHIVE_ITEM_ID, R.drawable.call);
            item.setContentDescription("AI Call Summaries");
        } else if (currentUser != null && !currentUser.bot && !currentUser.self && headerItem != null) {
            headerItem.addSubItem(CHAT_MENU_AI_CALL_ID, R.drawable.msg_callback, "AI Call");
            headerItem.addSubItem(CHAT_MENU_CALLER_DATA_ID, R.drawable.msg_info, "AI Caller Data");
        }
    }

    public static boolean onChatMenuItemClick(BaseFragment fragment, TLRPC.User currentUser, int itemId) {
        if (fragment == null) return false;
        if (itemId == SAVED_MESSAGES_ARCHIVE_ITEM_ID) {
            fragment.presentFragment(new AiCallHistoryActivity());
            return true;
        }
        if (currentUser != null && !currentUser.bot && !currentUser.self) {
            if (itemId == CHAT_MENU_AI_CALL_ID) {
                showInChatAiCallDialog(fragment, currentUser);
                return true;
            }
            if (itemId == CHAT_MENU_CALLER_DATA_ID) {
                showInChatCallerDataDialog(fragment, currentUser);
                return true;
            }
        }
        return false;
    }

    private static void showInChatAiCallDialog(final BaseFragment fragment, final TLRPC.User user) {
        Context context = fragment.getContext();
        if (context == null || fragment.getParentActivity() == null) return;

        final EditTextBoldCursor goalInput = new EditTextBoldCursor(context);
        goalInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        goalInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        goalInput.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        goalInput.setHint("Call purpose/goal (optional, e.g. Ask about tonight's meeting)");
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        goalInput.setMinLines(2);
        goalInput.setMaxLines(5);

        FrameLayout box = new FrameLayout(context);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        box.addView(goalInput, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String name = ContactsController.formatName(user.first_name, user.last_name);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("AI Call -> " + name);
        builder.setView(box);
        builder.setPositiveButton("Start AI Call", (d, w) -> {
            String goal = goalInput.getText() != null ? goalInput.getText().toString().trim() : "";
            AiCallController.getInstance().prepareAiOutgoingCall(user.id, goal);
            VoIPHelper.startCall(user, false, false, fragment.getParentActivity(), null, AccountInstance.getInstance(UserConfig.selectedAccount));
        });
        builder.setNegativeButton("Cancel", null);
        fragment.showDialog(builder.create());
    }

    private static void showInChatCallerDataDialog(final BaseFragment fragment, final TLRPC.User user) {
        Context context = fragment.getContext();
        if (context == null) return;

        final CallerProfileStore store = CallerProfileStore.getInstance();
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint("How should AI treat this person? (e.g. Boss, polite formal; or Close friend, casual)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(7);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(store.getCallerData(user.id));

        FrameLayout box = new FrameLayout(context);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String name = ContactsController.formatName(user.first_name, user.last_name);
        String rollingMem = store.getRollingMemory(user.id);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("AI Caller Data: " + name);
        if (!rollingMem.isEmpty()) {
            builder.setMessage("Auto 2-3 line past memory:\n" + rollingMem);
        }
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            store.setCallerData(user.id, val);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Caller data saved for " + name).show();
        });
        builder.setNeutralButton("Clear Memory", (d, w) -> {
            store.clearRollingMemory(user.id);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Past memory cleared").show();
        });
        builder.setNegativeButton("Cancel", null);
        fragment.showDialog(builder.create());
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
