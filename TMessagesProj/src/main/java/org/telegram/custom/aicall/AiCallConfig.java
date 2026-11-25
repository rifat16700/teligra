package org.telegram.custom.aicall;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Persistent configuration for the AI Auto-Answer & Composable Call system.
 * Stores user preferences, ring delay (0-60s), and owner knowledge/instructions.
 */
public final class AiCallConfig {
    private static final String PREFS_NAME = "custom_ai_call_prefs";
    private static final String KEY_AUTO_ANSWER_ENABLED = "auto_answer_enabled";
    private static final String KEY_RING_DELAY_SECONDS = "ring_delay_seconds";
    private static final String KEY_OWNER_DATA = "owner_data";
    private static final String KEY_AUTO_RECORD_SAVED = "auto_record_to_saved";
    private static final String KEY_ANONYMOUS_LISTEN_DEFAULT = "anonymous_listen_default";
    private static final String KEY_BACKGROUND_TRANSCRIPT = "background_transcript_enabled";

    public static final int MIN_RING_DELAY_SECONDS = 0;
    public static final int MAX_RING_DELAY_SECONDS = 60;
    public static final int DEFAULT_RING_DELAY_SECONDS = 10;

    private static AiCallConfig instance;
    private final SharedPreferences prefs;

    private AiCallConfig(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AiCallConfig getInstance() {
        if (instance == null) {
            instance = new AiCallConfig(ApplicationLoader.applicationContext);
        }
        return instance;
    }

    public boolean isAutoAnswerEnabled() {
        return prefs.getBoolean(KEY_AUTO_ANSWER_ENABLED, false);
    }

    public void setAutoAnswerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_ANSWER_ENABLED, enabled).apply();
    }

    public int getRingDelaySeconds() {
        int val = prefs.getInt(KEY_RING_DELAY_SECONDS, DEFAULT_RING_DELAY_SECONDS);
        return clampRingDelay(val);
    }

    public void setRingDelaySeconds(int seconds) {
        prefs.edit().putInt(KEY_RING_DELAY_SECONDS, clampRingDelay(seconds)).apply();
    }

    public String getOwnerData() {
        return prefs.getString(KEY_OWNER_DATA, "");
    }

    public void setOwnerData(String ownerData) {
        prefs.edit().putString(KEY_OWNER_DATA, ownerData == null ? "" : ownerData).apply();
    }

    public boolean isAutoRecordToSavedMessages() {
        return prefs.getBoolean(KEY_AUTO_RECORD_SAVED, true);
    }

    public void setAutoRecordToSavedMessages(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD_SAVED, enabled).apply();
    }

    public boolean isAnonymousListenDefault() {
        return prefs.getBoolean(KEY_ANONYMOUS_LISTEN_DEFAULT, false);
    }

    public void setAnonymousListenDefault(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANONYMOUS_LISTEN_DEFAULT, enabled).apply();
    }

    public boolean isBackgroundTranscriptEnabled() {
        return prefs.getBoolean(KEY_BACKGROUND_TRANSCRIPT, true);
    }

    public void setBackgroundTranscriptEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BACKGROUND_TRANSCRIPT, enabled).apply();
    }

    public static int clampRingDelay(int seconds) {
        if (seconds < MIN_RING_DELAY_SECONDS) return MIN_RING_DELAY_SECONDS;
        if (seconds > MAX_RING_DELAY_SECONDS) return MAX_RING_DELAY_SECONDS;
        return seconds;
    }
}
