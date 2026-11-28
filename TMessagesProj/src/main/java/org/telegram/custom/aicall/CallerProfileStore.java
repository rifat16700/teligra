package org.telegram.custom.aicall;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Per-caller persistent profile store:
 * 1. Manual Caller Data (user-configured notes about how to handle a specific contact).
 * 2. Auto Rolling Memory (concise 2-3 line past-call context saved after each call,
 *    low-priority supplementary context that never overrides Owner Data).
 */
public final class CallerProfileStore {
    private static final String PREFS_NAME = "custom_ai_caller_profiles";
    private static final String KEY_CALLER_DATA_PREFIX = "caller_data_";
    private static final String KEY_ROLLING_MEMORY_PREFIX = "rolling_mem_";
    private static final int MAX_ROLLING_MEMORY_CHARS = 320;

    private static CallerProfileStore instance;
    private final SharedPreferences prefs;

    private CallerProfileStore(Context context) {
        Context appContext = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized CallerProfileStore getInstance() {
        if (instance == null) {
            instance = new CallerProfileStore(ApplicationLoader.applicationContext);
        }
        return instance;
    }

    public String getCallerData(long userId) {
        if (userId == 0L) return "";
        return prefs.getString(KEY_CALLER_DATA_PREFIX + userId, "");
    }

    public void setCallerData(long userId, String data) {
        if (userId == 0L) return;
        prefs.edit().putString(KEY_CALLER_DATA_PREFIX + userId, data == null ? "" : data.trim()).apply();
    }

    public String getRollingMemory(long userId) {
        if (userId == 0L) return "";
        return prefs.getString(KEY_ROLLING_MEMORY_PREFIX + userId, "");
    }

    public void setRollingMemory(long userId, String shortNote) {
        if (userId == 0L) return;
        String clean = shortNote == null ? "" : shortNote.trim();
        if (clean.length() > MAX_ROLLING_MEMORY_CHARS) {
            clean = clean.substring(0, MAX_ROLLING_MEMORY_CHARS - 3) + "...";
        }
        prefs.edit().putString(KEY_ROLLING_MEMORY_PREFIX + userId, clean).apply();
    }

    public void clearRollingMemory(long userId) {
        if (userId == 0L) return;
        prefs.edit().remove(KEY_ROLLING_MEMORY_PREFIX + userId).apply();
    }
}
