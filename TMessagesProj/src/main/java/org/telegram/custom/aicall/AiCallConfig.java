package org.telegram.custom.aicall;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Persistent configuration for the AI Auto-Answer & Composable Call system.
 * Stores user preferences, ring delay (0-60s), owner knowledge/instructions,
 * and local BYOK Gemini Live API settings.
 */
public final class AiCallConfig {
    private static final String PREFS_NAME = "custom_ai_call_prefs";
    private static final String KEY_AUTO_ANSWER_ENABLED = "auto_answer_enabled";
    private static final String KEY_RING_DELAY_SECONDS = "ring_delay_seconds";
    private static final String KEY_OWNER_DATA = "owner_data";
    private static final String KEY_AUTO_RECORD_SAVED = "auto_record_to_saved";
    private static final String KEY_ANONYMOUS_LISTEN_DEFAULT = "anonymous_listen_default";
    private static final String KEY_BACKGROUND_TRANSCRIPT = "background_transcript_enabled";

    // BYOK Gemini Live WebSocket configuration (stored locally on user's device)
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_MODEL_ID = "gemini_model_id";
    private static final String KEY_API_VERSION = "gemini_api_version";
    private static final String KEY_VOICE_NAME = "gemini_voice_name";
    private static final String KEY_THINKING_LEVEL = "gemini_thinking_level";

    public static final int MIN_RING_DELAY_SECONDS = 0;
    public static final int MAX_RING_DELAY_SECONDS = 60;
    public static final int DEFAULT_RING_DELAY_SECONDS = 10;

    public static final String[] MODEL_IDS = {
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking",
            "gemini-3.5-transcribe-live",
            "gemini-3.5-live-translate-preview",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
    };

    public static final String[] MODEL_LABELS = {
            "Gemini 3.8 Live",
            "Gemini 3.8 Live Extended Thinking",
            "Gemini 3.5 Transcribe Live",
            "Gemini 3.5 Live Translate",
            "Gemini 3 Flash Live",
            "Gemini 2.5 Flash Native Audio Dialog"
    };

    public static final String[] VOICE_NAMES = {
            "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr"
    };

    public static final String[] API_VERSIONS = {
            "v1beta", "v1alpha"
    };

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

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public String getModelId() {
        String id = prefs.getString(KEY_MODEL_ID, MODEL_IDS[0]);
        return (id == null || id.trim().isEmpty()) ? MODEL_IDS[0] : id.trim();
    }

    public void setModelId(String modelId) {
        String value = (modelId == null || modelId.trim().isEmpty()) ? MODEL_IDS[0] : modelId.trim();
        prefs.edit().putString(KEY_MODEL_ID, value).apply();
    }

    public String getApiVersion() {
        String ver = prefs.getString(KEY_API_VERSION, "v1beta");
        return "v1alpha".equals(ver) ? "v1alpha" : "v1beta";
    }

    public void setApiVersion(String apiVersion) {
        prefs.edit().putString(KEY_API_VERSION, "v1alpha".equals(apiVersion) ? "v1alpha" : "v1beta").apply();
    }

    public String getVoiceName() {
        String voice = prefs.getString(KEY_VOICE_NAME, "Kore");
        return (voice == null || voice.trim().isEmpty()) ? "Kore" : voice.trim();
    }

    public void setVoiceName(String voiceName) {
        String value = (voiceName == null || voiceName.trim().isEmpty()) ? "Kore" : voiceName.trim();
        prefs.edit().putString(KEY_VOICE_NAME, value).apply();
    }

    public String getThinkingLevel() {
        String level = prefs.getString(KEY_THINKING_LEVEL, "OFF");
        if ("LOW".equals(level) || "HIGH".equals(level)) return level;
        return "OFF";
    }

    public void setThinkingLevel(String level) {
        String value = ("LOW".equals(level) || "HIGH".equals(level)) ? level : "OFF";
        prefs.edit().putString(KEY_THINKING_LEVEL, value).apply();
    }

    public void applyModelPreset(String modelId) {
        String target = (modelId == null || modelId.trim().isEmpty()) ? MODEL_IDS[0] : modelId.trim();
        boolean isExtendedThinking = "gemini-3.8-live-extended-thinking".equals(target);
        prefs.edit()
                .putString(KEY_MODEL_ID, target)
                .putString(KEY_API_VERSION, isExtendedThinking ? "v1alpha" : "v1beta")
                .putString(KEY_THINKING_LEVEL, isExtendedThinking ? "HIGH" : "OFF")
                .apply();
    }

    public String getPresetLabelForModel(String modelId) {
        if (modelId == null) return "Custom";
        for (int i = 0; i < MODEL_IDS.length; i++) {
            if (MODEL_IDS[i].equals(modelId)) {
                return MODEL_LABELS[i];
            }
        }
        return "Custom (" + modelId + ")";
    }

    public static int clampRingDelay(int seconds) {
        if (seconds < MIN_RING_DELAY_SECONDS) return MIN_RING_DELAY_SECONDS;
        if (seconds > MAX_RING_DELAY_SECONDS) return MAX_RING_DELAY_SECONDS;
        return seconds;
    }
}
