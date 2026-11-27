package org.telegram.custom.aicall;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Persistent configuration for the AI Auto-Answer & Composable Call system.
 * Includes 7 pre-built use-case profiles, all 30 Gemini Studio voices,
 * emotion/speaking-style presets, owner knowledge/instructions, and BYOK Gemini Live settings.
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
    private static final String KEY_DUAL_MODEL_ENABLED = "dual_model_enabled";

    // Pre-built use-case profiles & emotion styling
    private static final String KEY_PROFILE_ID = "ai_profile_id";
    private static final String KEY_EMOTION_ID = "ai_emotion_id";
    private static final String KEY_CUSTOM_EMOTION = "ai_custom_emotion";

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
            "Gemini 3.8 Live (Recommended)",
            "Gemini 3.8 Live Extended Thinking",
            "Gemini 3.5 Transcribe Live",
            "Gemini 3.5 Live Translate",
            "Gemini 3 Flash Live",
            "Gemini 2.5 Flash Native Audio (Affective)"
    };

    public static final String[] VOICE_NAMES = {
            "Sulafat", "Achird", "Kore", "Vindemiatrix", "Zubenelgenubi",
            "Sadachbia", "Puck", "Zephyr", "Charon", "Fenrir",
            "Aoede", "Leda", "Orus", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina",
            "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Sadaltager"
    };

    public static final String[] VOICE_LABELS = {
            "Sulafat — Warm", "Achird — Friendly", "Kore — Firm", "Vindemiatrix — Gentle", "Zubenelgenubi — Casual",
            "Sadachbia — Lively", "Puck — Upbeat", "Zephyr — Bright", "Charon — Informative", "Fenrir — Excitable",
            "Aoede — Breezy", "Leda — Youthful", "Orus — Firm", "Callirrhoe — Easy-going", "Autonoe — Bright",
            "Enceladus — Breathy", "Iapetus — Clear", "Umbriel — Easy-going", "Algieba — Smooth", "Despina — Smooth",
            "Erinome — Clear", "Algenib — Gravelly", "Rasalgethi — Informative", "Laomedeia — Upbeat", "Achernar — Soft",
            "Alnilam — Firm", "Schedar — Even", "Gacrux — Mature", "Pulcherrima — Forward", "Sadaltager — Knowledgeable"
    };

    public static final String[] PROFILE_IDS = {
            "personal", "business", "busy", "support", "family", "privacy", "custom"
    };

    public static final String[] PROFILE_LABELS = {
            "Personal Assistant",
            "Business / Receptionist",
            "Busy / Driving / Meeting",
            "Customer Support / Sales",
            "Friends & Family (Casual)",
            "Strict Privacy / Screening",
            "Custom Profile"
    };

    public static final String[] PROFILE_PROMPTS = {
            "Act as the owner's polite personal assistant. Greet warmly, explain availability from Owner Data, and offer to take a clear message.",
            "Act as a courteous business receptionist. Speak professionally, answer inquiries strictly using Owner Data, and collect caller name, company, and callback reason.",
            "The owner is currently busy, driving, or in a meeting. Keep answers under 15 seconds, ask if it is urgent, and promise a callback.",
            "Act as a helpful customer support and sales assistant. Be upbeat, answer product or service questions using Owner Data, and note follow-up requests.",
            "Speak like a friendly, natural human speaking to friends or family. Keep it relaxed, warm, and conversational while sharing the owner's status.",
            "Be polite yet cautious with unknown callers. Do not share private personal details unless the caller clearly identifies themselves and their purpose.",
            ""
    };

    public static final String[] PROFILE_DEFAULT_VOICES = {
            "Sulafat", "Kore", "Charon", "Achird", "Zubenelgenubi", "Orus", "Sulafat"
    };

    public static final String[] PROFILE_DEFAULT_EMOTIONS = {
            "warm", "professional", "concise", "friendly", "casual", "professional", "warm"
    };

    public static final String[] EMOTION_IDS = {
            "warm", "friendly", "professional", "empathetic", "energetic", "casual", "concise", "custom"
    };

    public static final String[] EMOTION_LABELS = {
            "Natural & Warm",
            "Friendly & Cheerful",
            "Calm & Professional",
            "Empathetic & Soft",
            "Energetic & Lively",
            "Relaxed & Human Casual",
            "Serious & Concise",
            "Custom Emotion Style..."
    };

    public static final String[] EMOTION_INSTRUCTIONS = {
            "Speak with genuine warmth, natural human rhythm, soft pauses, and polite emotional inflection—never robotic.",
            "Sound genuinely cheerful, smiling, welcoming, and upbeat while keeping responses natural and concise.",
            "Use a calm, steady, confident, and polished professional tone with clear pacing.",
            "Use a gentle, caring, empathetic tone—soften your voice if the caller sounds stressed or upset.",
            "Sound lively, enthusiastic, and expressive with natural energy in your delivery.",
            "Speak casually and effortlessly like a close friend—natural pauses, relaxed phrasing, zero stiffness.",
            "Keep your tone serious, direct, respectful, and ultra-concise with no filler.",
            ""
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
        String voice = prefs.getString(KEY_VOICE_NAME, "Sulafat");
        return (voice == null || voice.trim().isEmpty()) ? "Sulafat" : voice.trim();
    }

    public void setVoiceName(String voiceName) {
        String value = (voiceName == null || voiceName.trim().isEmpty()) ? "Sulafat" : voiceName.trim();
        prefs.edit().putString(KEY_VOICE_NAME, value).apply();
    }

    public String getVoiceDisplayLabel(String voiceName) {
        for (int i = 0; i < VOICE_NAMES.length; i++) {
            if (VOICE_NAMES[i].equals(voiceName)) {
                return VOICE_LABELS[i];
            }
        }
        return voiceName;
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

    public boolean isDualModelEnabled() {
        return prefs.getBoolean(KEY_DUAL_MODEL_ENABLED, true);
    }

    public void setDualModelEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DUAL_MODEL_ENABLED, enabled).apply();
    }

    public String getProfileId() {
        String id = prefs.getString(KEY_PROFILE_ID, PROFILE_IDS[0]);
        return (id == null || id.trim().isEmpty()) ? PROFILE_IDS[0] : id.trim();
    }

    public void applyUseCaseProfile(String profileId) {
        int idx = 0;
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(profileId)) {
                idx = i;
                break;
            }
        }
        SharedPreferences.Editor ed = prefs.edit().putString(KEY_PROFILE_ID, PROFILE_IDS[idx]);
        if (!"custom".equals(PROFILE_IDS[idx])) {
            ed.putString(KEY_VOICE_NAME, PROFILE_DEFAULT_VOICES[idx]);
            ed.putString(KEY_EMOTION_ID, PROFILE_DEFAULT_EMOTIONS[idx]);
        }
        ed.apply();
    }

    public String getProfileLabel() {
        String id = getProfileId();
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(id)) return PROFILE_LABELS[i];
        }
        return PROFILE_LABELS[0];
    }

    public String getEffectiveProfileInstruction() {
        String id = getProfileId();
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(id)) return PROFILE_PROMPTS[i];
        }
        return "";
    }

    public String getEmotionId() {
        String id = prefs.getString(KEY_EMOTION_ID, EMOTION_IDS[0]);
        return (id == null || id.trim().isEmpty()) ? EMOTION_IDS[0] : id.trim();
    }

    public void setEmotionId(String emotionId) {
        String val = (emotionId == null || emotionId.trim().isEmpty()) ? EMOTION_IDS[0] : emotionId.trim();
        prefs.edit().putString(KEY_EMOTION_ID, val).apply();
    }

    public String getCustomEmotion() {
        return prefs.getString(KEY_CUSTOM_EMOTION, "");
    }

    public void setCustomEmotion(String customEmotion) {
        prefs.edit().putString(KEY_CUSTOM_EMOTION, customEmotion == null ? "" : customEmotion.trim()).apply();
    }

    public String getEmotionLabel() {
        String id = getEmotionId();
        if ("custom".equals(id)) {
            String custom = getCustomEmotion();
            return custom.isEmpty() ? "Custom" : ("Custom: " + (custom.length() > 22 ? custom.substring(0, 22) + "..." : custom));
        }
        for (int i = 0; i < EMOTION_IDS.length; i++) {
            if (EMOTION_IDS[i].equals(id)) return EMOTION_LABELS[i];
        }
        return EMOTION_LABELS[0];
    }

    public String getEffectiveEmotionInstruction() {
        String id = getEmotionId();
        if ("custom".equals(id)) {
            return getCustomEmotion();
        }
        for (int i = 0; i < EMOTION_IDS.length; i++) {
            if (EMOTION_IDS[i].equals(id)) return EMOTION_INSTRUCTIONS[i];
        }
        return EMOTION_INSTRUCTIONS[0];
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
        return modelId;
    }

    public static int clampRingDelay(int seconds) {
        if (seconds < MIN_RING_DELAY_SECONDS) return MIN_RING_DELAY_SECONDS;
        if (seconds > MAX_RING_DELAY_SECONDS) return MAX_RING_DELAY_SECONDS;
        return seconds;
    }
}
