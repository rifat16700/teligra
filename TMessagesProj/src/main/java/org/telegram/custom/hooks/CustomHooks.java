package org.telegram.custom.hooks;

import android.content.Context;
import android.os.Bundle;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.custom.features.FeatureManager;
import org.telegram.custom.utils.CustomLogger;

public final class CustomHooks {
    private static final String TAG = "CustomHooks";

    private CustomHooks() {}

    public static void onApplicationCreate(Context context) {
        CustomLogger.d(TAG, "onApplicationCreate");
        FeatureManager.getInstance().initialize(context);
    }

    public static void onLaunchActivityCreate(LaunchActivity activity, Bundle savedInstanceState) {
        CustomLogger.d(TAG, "onLaunchActivityCreate");
        FeatureManager.getInstance().onLaunchCreate(activity, savedInstanceState);
    }

    public static void onLaunchActivityResume(LaunchActivity activity) {
        CustomLogger.d(TAG, "onLaunchActivityResume");
        FeatureManager.getInstance().onLaunchResume(activity);
    }

    public static void onDialogsActivityCreate(DialogsActivity activity, Bundle savedInstanceState) {
        CustomLogger.d(TAG, "onDialogsActivityCreate");
        FeatureManager.getInstance().onDialogsCreate(activity, savedInstanceState);
    }

    public static void onDialogsActivityResume(DialogsActivity activity) {
        CustomLogger.d(TAG, "onDialogsActivityResume");
        FeatureManager.getInstance().onDialogsResume(activity);
    }

    public static void onChatActivityCreate(ChatActivity activity, Bundle savedInstanceState) {
        CustomLogger.d(TAG, "onChatActivityCreate");
        FeatureManager.getInstance().onChatCreate(activity, savedInstanceState);
    }

    public static void onChatActivityResume(ChatActivity activity) {
        CustomLogger.d(TAG, "onChatActivityResume");
        FeatureManager.getInstance().onChatResume(activity);
    }

    public static void onChatActivityPause(ChatActivity activity) {
        CustomLogger.d(TAG, "onChatActivityPause");
        FeatureManager.getInstance().onChatPause(activity);
    }

    public static void onChatActivityDestroy(ChatActivity activity) {
        CustomLogger.d(TAG, "onChatActivityDestroy");
        FeatureManager.getInstance().onChatDestroy(activity);
    }

    public static void onNewIntentReceived(Context context, android.content.Intent intent) {
        CustomLogger.d(TAG, "onNewIntentReceived");
        FeatureManager.getInstance().handleNewIntent(context, intent);
    }

    public static void onMessageReceived(long dialogId, Object messageObject) {
        CustomLogger.d(TAG, "onMessageReceived: " + dialogId);
        FeatureManager.getInstance().onMessageReceived(dialogId, messageObject);
    }

    public static void onMessageSent(long dialogId, Object messageObject) {
        CustomLogger.d(TAG, "onMessageSent: " + dialogId);
        FeatureManager.getInstance().onMessageSent(dialogId, messageObject);
    }
}