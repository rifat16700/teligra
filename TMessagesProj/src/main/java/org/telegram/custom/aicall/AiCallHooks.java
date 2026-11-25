package org.telegram.custom.aicall;

import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.SettingsActivity;

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
}
