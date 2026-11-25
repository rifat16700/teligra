package org.telegram.custom.utils;

import android.util.Log;

public final class CustomLogger {
    private static final boolean DEBUG = true;
    private static final String PREFIX = "[CUSTOM] ";

    private CustomLogger() {}

    public static void d(String tag, String message) {
        if (DEBUG) Log.d(PREFIX + tag, message);
    }

    public static void i(String tag, String message) {
        if (DEBUG) Log.i(PREFIX + tag, message);
    }

    public static void w(String tag, String message) {
        if (DEBUG) Log.w(PREFIX + tag, message);
    }

    public static void e(String tag, String message) {
        if (DEBUG) Log.e(PREFIX + tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        if (DEBUG) Log.e(PREFIX + tag, message, throwable);
    }
}