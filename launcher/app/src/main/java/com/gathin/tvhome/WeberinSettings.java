package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings drawn in the same 1920 x 1080 coordinate space as the Weberin scene. */
final class WeberinSettings {
    interface Host {
        Activity activity();
        SharedPreferences preferences();
        void onSettingsChanged();
        void onCloseSettings();
        void onOriginalInterface();
        void onLauncherSettings();
        void onWallpaperPicker();
        void onWeatherCityPicker();
        void onReleaseLauncherMemory();
    }

    private static final int[] CATEGORIES = {R.string.weberin_settings_apps, R.string.weberin_settings_system,
            R.string.weberin_settings_display, R.string.weberin_settings_sound,
            R.string.weberin_settings_themes, R.string.weberin_settings_other};
    private static final int[] SUBTITLES = {R.string.ws_installed_apps, R.string.ws_system_hint,
            R.string.ws_display_hint, R.string.ws_sound_hint, R.string.ws_themes_hint, R.string.ws_other_hint};
    private final Host host;
    private final Activity activity;
    private final SharedPreferences preferences;
    private final List<ResolveInfo> apps;
    private final List<TextView> categories = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();
    private FrameLayout overlay;
    private LinearLayout rows;
    private FrameLayout details;
    private int category;
    private TextView title;
    private TextView subtitle;
    private ScrollView scroll;
    private TextClock sidebarTime;
    private TextClock sidebarDate;

    WeberinSettings(Host host, List<ResolveInfo> apps) {
        this.host = host;
        activity = host.activity();
        preferences = host.preferences();
        this.apps = new ArrayList<>(apps);
    }

    FrameLayout create() {
        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(1920, 1080));
        overlay.setBackgroundColor(0x22000000);
        overlay.setFocusable(false);
        details = new FrameLayout(activity);
        details.setBackground(panel());
        place(overlay, details, 962, 33, 596, 1014);

        title = text("", 30, Color.WHITE, true);
        place(details, title, 40, 38, 516, 45);
        subtitle = text("", 16, 0xffa9b1be, false);
        subtitle.setMaxLines(2);
        place(details, subtitle, 40, 87, 516, 30);
        scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 0, 0, 24);
        rows.setClipToPadding(false);
        scroll.addView(rows, new ScrollView.LayoutParams(516, ViewGroup.LayoutParams.WRAP_CONTENT));
        place(details, scroll, 40, 150, 516, 840);

        FrameLayout sidebar = new FrameLayout(activity);
        sidebar.setBackground(panel());
        place(overlay, sidebar, 1586, 33, 300, 1014);
        ImageView gear = new ImageView(activity);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        place(sidebar, gear, 63, 38, 20, 20);
        View divider = new View(activity);
        divider.setBackgroundColor(0xff18181a);
        place(sidebar, divider, 98, 22, 1, 48);
        TextClock time = new TextClock(activity);
        sidebarTime = time;
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm a");
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32);
        time.setTextColor(Color.WHITE);
        time.setTypeface(WeberinUi.font(activity, 400));
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER_VERTICAL);
        place(sidebar, time, 114, 18, 175, 39);
        TextClock date = new TextClock(activity);
        sidebarDate = date;
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        WeberinUi.capitalizeDate(date);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14);
        date.setTextColor(0xffc2c7cf);
        date.setTypeface(WeberinUi.font(activity, 400));
        date.setIncludeFontPadding(false);
        place(sidebar, date, 114, 59, 175, 22);
        ClockFormat.apply(sidebar, Config.load(activity).clockFormat);

        for (int i = 0; i < CATEGORIES.length; i++) {
            int index = i;
            TextView item = text(activity.getString(CATEGORIES[i]), 24, 0xffbfbfbf, true);
            item.setGravity(Gravity.CENTER);
            item.setFocusableInTouchMode(true);
            item.setId(View.generateViewId());
            item.setOnFocusChangeListener((view, focused) -> {
                if (focused && category != index) fill(index);
                updateCategoryAppearance();
            });
            item.setOnClickListener(view -> {
                if (category != index) fill(index);
                if (!controls.isEmpty()) controls.get(0).requestFocus();
            });
            categories.add(item);
            place(sidebar, item, 32, 108 + i * 70, 236, 50);
        }
        for (int i = 0; i < categories.size(); i++) {
            TextView item = categories.get(i);
            item.setNextFocusUpId(categories.get(Math.max(0, i - 1)).getId());
            item.setNextFocusDownId(categories.get(Math.min(categories.size() - 1, i + 1)).getId());
            item.setNextFocusRightId(item.getId());
        }
        fill(0);
        categories.get(0).post(() -> categories.get(0).requestFocus());
        return overlay;
    }

    /** Back returns from a setting to its category, then closes the two panels. */
    boolean handleBack() {
        View focus = overlay == null ? null : overlay.findFocus();
        if (isInside(focus, details)) categories.get(category).requestFocus();
        else host.onCloseSettings();
        return true;
    }

    void refresh() {
        if (rows == null) return;
        View focus = overlay.findFocus();
        int index = controls.indexOf(focus);
        fill(category);
        if (index >= 0 && !controls.isEmpty()) controls.get(Math.min(index, controls.size() - 1)).requestFocus();
    }

    private void fill(int index) {
        category = index;
        title.setText(CATEGORIES[index]);
        subtitle.setText(SUBTITLES[index]);
        rows.removeAllViews();
        controls.clear();
        scroll.scrollTo(0, 0);
        Config config = Config.load(activity);
        switch (index) {
            case 0:
                for (ResolveInfo app : apps) appRow(app);
                if (apps.isEmpty()) note(R.string.weberin_no_apps);
                break;
            case 1:
                action(R.string.tv_settings, () -> open(SystemSettings.intent(activity, Settings.ACTION_SETTINGS)));
                action(R.string.ws_network, () -> open(SystemSettings.intent(activity, Settings.ACTION_WIFI_SETTINGS)));
                action(R.string.ws_bluetooth, () -> open(SystemSettings.intent(activity, Settings.ACTION_BLUETOOTH_SETTINGS)));
                action(R.string.ws_date_time, () -> open(SystemSettings.intent(activity, Settings.ACTION_DATE_SETTINGS)));
                action(R.string.ws_accessibility, () -> open(SystemSettings.intent(activity, Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                action(R.string.ws_storage, () -> open(SystemSettings.intent(activity, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
                allSettings();
                break;
            case 2:
                action(R.string.ws_brightness, () -> open(SystemSettings.displayIntent(activity)));
                note(R.string.ws_brightness_hint);
                toggle(R.string.weberin_clock, "weberin_show_clock", true);
                toggle(R.string.weberin_favorites, "weberin_show_favorites", config.dockRow);
                toggle(R.string.ws_show_brand, "weberin_show_brand", true);
                toggle(R.string.ws_show_date, "weberin_show_date", true);
                toggle(R.string.ws_show_hints, "weberin_show_hints", true);
                toggle(R.string.ws_show_app_names, "weberin_show_app_names", true);
                toggle(R.string.weberin_weather_title, "weberin_show_weather",
                        preferences.getBoolean("weberin_home_full", false));
                action(activity.getString(R.string.weberin_weather_city) + ": "
                        + preferences.getString("weberin_weather_city", activity.getString(R.string.weberin_weather_default_city)),
                        host::onWeatherCityPicker);
                toggle(R.string.visibility_network, "visible_network", config.showNetworkPill);
                integerChoice(R.string.columns_label, "columns", new int[]{4, 5, 6, 7},
                        preferences.contains("columns") ? config.columns : 5, false);
                integerChoice(R.string.dock_opacity_label, Config.DOCK_OPACITY_KEY,
                        new int[]{8, 30, 50, 70, 100}, preferences.contains(Config.DOCK_OPACITY_KEY) ? config.dockOpacity : 8, true);
                clockFormat();
                toggle(R.string.ws_animations, "animations", config.animations);
                allSettings();
                break;
            case 3:
                volume();
                note(R.string.ws_volume_hint);
                action(R.string.ws_sound_settings, () -> open(SystemSettings.intent(activity, Settings.ACTION_SOUND_SETTINGS)));
                break;
            case 4:
                homeMode();
                note(R.string.ws_home_mode_hint);
                action(R.string.weberin_wallpaper, host::onWallpaperPicker);
                toggle(R.string.ws_surface_tint, "weberin_surface_tint", false);
                toggle(R.string.ws_native_banners, "weberin_native_banners", false);
                toggle(R.string.ws_show_xbox, "weberin_show_xbox", true);
                toggle(R.string.ws_show_news, "weberin_show_news", true);
                action(R.string.layout_original, host::onOriginalInterface);
                note(R.string.ws_original_hint);
                allSettings();
                break;
            default:
                memory();
                action(R.string.ws_release_memory, () -> {
                    host.onReleaseLauncherMemory();
                    Toast.makeText(activity, R.string.ws_memory_released, Toast.LENGTH_SHORT).show();
                    refresh();
                });
                note(R.string.ws_release_memory_hint);
                action(R.string.ws_clear_cache, this::confirmClearCache);
                action(R.string.ws_light_mode, this::confirmLightMode);
                note(R.string.ws_memory_limits);
                action(R.string.ws_storage, () -> open(SystemSettings.intent(activity, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
                allSettings();
                note(R.string.ws_channels_hint);
                break;
        }
        updateNavigation();
        updateCategoryAppearance();
    }

    private void appRow(ResolveInfo app) {
        FrameLayout row = new FrameLayout(activity);
        row.setFocusableInTouchMode(true);
        row.setId(View.generateViewId());
        row.setOnFocusChangeListener((v, focused) -> {
            row.setBackground(focused ? outline(0xff161618, Color.WHITE, 12) : null);
            updateCategoryAppearance();
        });
        ImageView icon = new ImageView(activity);
        icon.setPadding(6, 6, 6, 6);
        icon.setBackground(outline(0xff292c31, 0, 14));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { icon.setImageDrawable(app.loadIcon(activity.getPackageManager())); }
        catch (RuntimeException ignored) { }
        place(row, icon, 0, 0, 58, 58);
        TextView name = text(app.loadLabel(activity.getPackageManager()).toString(), 22, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        place(row, name, 76, 0, 386, 58);
        TextView arrow = text("›", 30, 0xffaeb4bf, false);
        arrow.setGravity(Gravity.CENTER);
        place(row, arrow, 470, 0, 30, 58);
        View separator = new View(activity);
        separator.setBackgroundColor(0xff242426);
        place(row, separator, 40, 75, 476, 1);
        row.setOnClickListener(view -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.activityInfo.packageName)), new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        rows.addView(row, new LinearLayout.LayoutParams(516, 94));
        controls.add(row);
    }

    private TextView action(int label, Runnable action) {
        return action(activity.getString(label), action);
    }

    private TextView action(String label, Runnable action) {
        TextView row = text(label + "  ›", 23, Color.WHITE, false);
        row.setFocusableInTouchMode(true);
        row.setId(View.generateViewId());
        row.setPadding(16, 8, 16, 8);
        row.setMaxLines(2);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setBackground(outline(0x00111111, 0, 12));
        row.setOnFocusChangeListener((view, focused) -> {
            row.setBackground(outline(focused ? 0xff18181b : 0x00111111, focused ? Color.WHITE : 0, 12));
            updateCategoryAppearance();
        });
        row.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(516, 74);
        params.bottomMargin = 8;
        rows.addView(row, params);
        controls.add(row);
        return row;
    }

    private void allSettings() {
        action(R.string.ws_all_settings, host::onLauncherSettings);
    }

    private void note(int resource) { note(activity.getString(resource)); }

    private void note(String value) {
        TextView note = text(value, 18, 0xffa9b1be, false);
        note.setGravity(Gravity.TOP);
        note.setPadding(16, 4, 16, 20);
        rows.addView(note, new LinearLayout.LayoutParams(516, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void toggle(int label, String key, boolean fallback) {
        String name = activity.getString(label);
        TextView row = action("", () -> { });
        Runnable update = () -> row.setText(name + ": " + activity.getString(
                preferences.getBoolean(key, fallback) ? R.string.on : R.string.off));
        row.setOnClickListener(view -> {
            boolean enabled = !preferences.getBoolean(key, fallback);
            SharedPreferences.Editor edit = preferences.edit().putBoolean(key, enabled);
            if ("animations".equals(key) && !enabled) edit.putBoolean("advanced_effects", false);
            edit.apply();
            changed();
            update.run();
        });
        update.run();
    }

    private void integerChoice(int label, String key, int[] values, int fallback, boolean percent) {
        String name = activity.getString(label);
        TextView row = action("", () -> { });
        Runnable update = () -> row.setText(name + ": " + preferences.getInt(key, fallback) + (percent ? "%" : ""));
        row.setOnClickListener(view -> {
            String[] options = new String[values.length];
            int selected = -1;
            for (int i = 0; i < values.length; i++) {
                options[i] = values[i] + (percent ? "%" : "");
                if (values[i] == preferences.getInt(key, fallback)) selected = i;
            }
            new AlertDialog.Builder(activity).setTitle(label).setSingleChoiceItems(options, selected, (dialog, which) -> {
                preferences.edit().putInt(key, values[which]).apply();
                changed();
                update.run();
                dialog.dismiss();
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
        update.run();
    }

    private void homeMode() {
        TextView row = action("", () -> { });
        Runnable update = () -> row.setText(activity.getString(R.string.ws_home_mode) + ": "
                + activity.getString(preferences.getBoolean("weberin_home_full", false)
                ? R.string.ws_home_full : R.string.ws_home_minimal));
        row.setOnClickListener(view -> {
            String[] choices = {activity.getString(R.string.ws_home_minimal), activity.getString(R.string.ws_home_full)};
            new AlertDialog.Builder(activity).setTitle(R.string.ws_home_mode)
                    .setSingleChoiceItems(choices, preferences.getBoolean("weberin_home_full", false) ? 1 : 0, (dialog, which) -> {
                        // An explicit weather choice remains independent when changing the home preset.
                        preferences.edit().putBoolean("weberin_home_full", which == 1).apply();
                        changed();
                        update.run();
                        dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        update.run();
    }

    private void clockFormat() {
        String[] values = {"system", "12", "24"};
        int[] labels = {R.string.clock_format_system, R.string.clock_format_12, R.string.clock_format_24};
        TextView row = action("", () -> { });
        Runnable update = () -> {
            String selected = preferences.getString(Config.CLOCK_FORMAT_KEY, Config.load(activity).clockFormat);
            int index = java.util.Arrays.asList(values).indexOf(selected);
            row.setText(activity.getString(R.string.ws_clock_format) + ": " + activity.getString(labels[Math.max(0, index)]));
        };
        row.setOnClickListener(view -> {
            String[] choices = new String[3];
            for (int i = 0; i < choices.length; i++) choices[i] = activity.getString(labels[i]);
            int selected = java.util.Arrays.asList(values).indexOf(
                    preferences.getString(Config.CLOCK_FORMAT_KEY, Config.load(activity).clockFormat));
            new AlertDialog.Builder(activity).setTitle(R.string.ws_clock_format)
                    .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                        preferences.edit().putString(Config.CLOCK_FORMAT_KEY, values[which]).apply();
                        changed();
                        update.run();
                        sidebarTime.setFormat12Hour("h:mm a");
                        sidebarTime.setFormat24Hour("HH:mm");
                        sidebarDate.setFormat12Hour("EEEE, d MMMM");
                        sidebarDate.setFormat24Hour("EEEE, d MMMM");
                        ClockFormat.apply(overlay, values[which]);
                        dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        update.run();
    }

    private void volume() {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        TextView row = action("", () -> open(SystemSettings.intent(activity, Settings.ACTION_SOUND_SETTINGS)));
        Runnable update = () -> row.setText(activity.getString(R.string.ws_volume,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        row.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                try {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            code == KeyEvent.KEYCODE_DPAD_LEFT ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE, 0);
                    update.run();
                } catch (SecurityException ignored) {
                    Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });
        update.run();
    }

    private void memory() {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            note(activity.getString(R.string.ws_memory_available, size(info.availMem), size(info.totalMem)));
        }
        Runtime runtime = Runtime.getRuntime();
        note(activity.getString(R.string.ws_launcher_heap, size(runtime.totalMemory() - runtime.freeMemory())));
        try {
            StatFs disk = new StatFs(activity.getFilesDir().getPath());
            note(activity.getString(R.string.ws_storage_available, size(disk.getAvailableBytes()), size(disk.getTotalBytes())));
        } catch (IllegalArgumentException ignored) { }
    }

    private String size(long bytes) { return Formatter.formatShortFileSize(activity, bytes); }

    private void confirmClearCache() {
        new AlertDialog.Builder(activity).setTitle(R.string.ws_clear_cache).setMessage(R.string.ws_clear_cache_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    java.io.File cache = activity.getCacheDir();
                    new Thread(() -> {
                        long removed = WeberinCache.clear(cache);
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            Toast.makeText(activity, activity.getString(R.string.ws_cache_cleared, size(removed)), Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }, "weberin-cache").start();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmLightMode() {
        new AlertDialog.Builder(activity).setTitle(R.string.ws_light_mode).setMessage(R.string.ws_light_mode_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    preferences.edit().putBoolean("animations", false).putBoolean("advanced_effects", false)
                            .putBoolean("weberin_surface_tint", false).apply();
                    host.onReleaseLauncherMemory();
                    changed();
                    Toast.makeText(activity, R.string.ws_light_mode_done, Toast.LENGTH_SHORT).show();
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void changed() {
        preferences.edit().putLong(Config.SETTINGS_REVISION_KEY,
                preferences.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        host.onSettingsChanged();
    }

    private void open(Intent... targets) {
        for (Intent target : targets) {
            try { activity.startActivity(target); return; }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        try { activity.startActivity(SystemSettings.intent(activity, Settings.ACTION_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateNavigation() {
        if (controls.isEmpty()) {
            for (TextView item : categories) item.setNextFocusLeftId(item.getId());
            return;
        }
        for (TextView item : categories) item.setNextFocusLeftId(controls.get(0).getId());
        for (int i = 0; i < controls.size(); i++) {
            View control = controls.get(i);
            control.setNextFocusLeftId(control.getId());
            control.setNextFocusRightId(categories.get(category).getId());
            control.setNextFocusUpId(controls.get(Math.max(0, i - 1)).getId());
            control.setNextFocusDownId(controls.get(Math.min(controls.size() - 1, i + 1)).getId());
        }
    }

    private void updateCategoryAppearance() {
        boolean inDetails = isInside(overlay == null ? null : overlay.findFocus(), details);
        for (int i = 0; i < categories.size(); i++) {
            TextView item = categories.get(i);
            boolean selected = i == category;
            item.setTextColor(selected ? Color.WHITE : 0xffbfbfbf);
            item.setBackground(selected ? outline(0x00111111,
                    inDetails ? 0xff777b81 : 0xffedf0f3, 12) : null);
        }
    }

    private static boolean isInside(View view, ViewGroup group) {
        if (view == null || group == null) return false;
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent == group) return true;
        return view == group;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        return WeberinUi.text(activity, value, size, color, bold);
    }

    private static GradientDrawable panel() {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff0b0b0c, 0xff020203});
        background.setCornerRadius(32);
        background.setStroke(1, 0xff77787a);
        return background;
    }

    private static GradientDrawable outline(int fill, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(radius);
        if (border != 0) background.setStroke(1, border);
        return background;
    }

    private static void place(FrameLayout parent, View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = x;
        params.topMargin = y;
        parent.addView(view, params);
    }
}
