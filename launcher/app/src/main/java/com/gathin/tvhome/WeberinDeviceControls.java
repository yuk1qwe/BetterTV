package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.WindowManager;

/** Standard Android controls only; a TV may keep its panel/backlight in a separate vendor menu. */
final class WeberinDeviceControls {
    static final String WINDOW_BRIGHTNESS = "weberin_launcher_brightness";

    private WeberinDeviceControls() { }

    static boolean canWriteSystemSettings(Context context) {
        try { return Settings.System.canWrite(context); }
        catch (RuntimeException ignored) { return false; }
    }

    /** -1 means the firmware does not expose this Android setting. */
    static int systemBrightness(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return value >= 0 && value <= 255 ? value : -1;
        } catch (Settings.SettingNotFoundException | RuntimeException ignored) { return -1; }
    }

    static boolean writeSystemBrightness(Context context, int value) {
        if (value < 0 || value > 255 || !canWriteSystemSettings(context)) return false;
        try {
            return Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value)
                    && systemBrightness(context) == value;
        } catch (RuntimeException ignored) { return false; }
    }

    static boolean automaticBrightness(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (RuntimeException ignored) { return false; }
    }

    /** Applies only to this activity's window; does not change device brightness or other apps. */
    static void applyWindowBrightness(Activity activity, SharedPreferences preferences) {
        int value = preferences.getInt(WINDOW_BRIGHTNESS, -1);
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.screenBrightness = value < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(20, Math.min(100, value)) / 100f;
        activity.getWindow().setAttributes(attributes);
    }
}
