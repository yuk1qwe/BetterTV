package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
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
        Config config();
        String appLabel(ResolveInfo app);
        void bindAppIcon(ResolveInfo app, ImageView target);
        void onSettingsChanged();
        void onCloseSettings();
        void onOriginalInterface();
        void onLauncherSettings();
        void onFavoritesEditor();
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
    private Runnable pageContent;
    private String pageTitle;
    private int pageSubtitle;
    private int parentControl;

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
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
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
        ClockFormat.apply(sidebar, host.config().clockFormat);

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
        if (pageContent != null) {
            int restore = parentControl;
            fill(category);
            if (!controls.isEmpty()) controls.get(Math.min(restore, controls.size() - 1)).requestFocus();
        } else if (isInside(focus, details)) categories.get(category).requestFocus();
        else host.onCloseSettings();
        return true;
    }

    void refresh() {
        if (rows == null) return;
        View focus = overlay.findFocus();
        int index = controls.indexOf(focus);
        if (pageContent == null) fill(category); else rebuildPage();
        if (index >= 0 && !controls.isEmpty()) controls.get(Math.min(index, controls.size() - 1)).requestFocus();
    }

    private void fill(int index) {
        category = index;
        pageContent = null;
        reset(activity.getString(CATEGORIES[index]), SUBTITLES[index]);
        switch (index) {
            case 0:
                action(R.string.ws_manage_favorites, host::onFavoritesEditor);
                note(R.string.ws_favorites_hint);
                for (ResolveInfo app : apps) appRow(app);
                if (apps.isEmpty()) note(R.string.weberin_no_apps);
                break;
            case 1:
                action(R.string.ws_device_info, () -> page(R.string.ws_device_info, R.string.ws_system_hint, this::deviceInfo));
                action(R.string.ws_connections, () -> page(R.string.ws_connections, R.string.ws_system_hint, this::connections));
                action(R.string.ws_special_access, () -> page(R.string.ws_special_access, R.string.ws_access_hint, this::specialAccess));
                action(R.string.ws_date_time, () -> page(R.string.ws_date_time, R.string.ws_system_hint, () -> {
                    clockFormat();
                    action(R.string.ws_system_date_time, () -> open(SystemSettings.intent(activity, Settings.ACTION_DATE_SETTINGS)));
                }));
                action(R.string.ws_tv_settings_external, () -> open(SystemSettings.intent(activity, Settings.ACTION_SETTINGS)));
                break;
            case 2:
                action(R.string.ws_home_widgets, () -> page(R.string.ws_home_widgets, R.string.ws_display_hint, this::homeWidgets));
                action(R.string.ws_grid_cards, () -> page(R.string.ws_grid_cards, R.string.ws_display_hint, this::gridCards));
                action(R.string.weberin_weather_title, () -> page(R.string.weberin_weather_title, R.string.ws_weather_layout_hint, this::weatherSettings));
                action(R.string.ws_brightness, () -> page(R.string.ws_brightness, R.string.ws_brightness_scope, this::brightness));
                action(R.string.ws_clock_format, () -> page(R.string.ws_clock_format, R.string.ws_display_hint, this::clockSettings));
                action(R.string.ws_effects, () -> page(R.string.ws_effects, R.string.ws_display_hint, this::effects));
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
                action(R.string.layout_original, host::onOriginalInterface);
                note(R.string.ws_original_hint);
                break;
            default:
                action(R.string.ws_memory, () -> page(R.string.ws_memory, R.string.ws_other_hint, this::memorySettings));
                action(R.string.ws_storage, () -> page(R.string.ws_storage, R.string.ws_other_hint, this::storage));
                action(R.string.ws_special_access, () -> page(R.string.ws_special_access, R.string.ws_access_hint, this::specialAccess));
                note(R.string.ws_original_advanced_hint);
                allSettings();
                note(R.string.ws_channels_hint);
                break;
        }
        updateNavigation();
        updateCategoryAppearance();
    }

    private void reset(String heading, int subtitleId) {
        title.setText(heading);
        subtitle.setText(subtitleId);
        rows.removeAllViews();
        controls.clear();
        scroll.scrollTo(0, 0);
    }

    private void page(int heading, int subtitleId, Runnable content) {
        page(activity.getString(heading), subtitleId, content);
    }

    private void page(String heading, int subtitleId, Runnable content) {
        if (pageContent == null) parentControl = Math.max(0, controls.indexOf(overlay.findFocus()));
        pageTitle = heading;
        pageSubtitle = subtitleId;
        pageContent = content;
        rebuildPage();
        if (!controls.isEmpty()) controls.get(0).requestFocus();
    }

    private void rebuildPage() {
        reset(pageTitle, pageSubtitle);
        pageContent.run();
        updateNavigation();
        updateCategoryAppearance();
    }

    private void homeWidgets() {
        Config config = host.config();
        homeMode();
        action(R.string.ws_manage_favorites, host::onFavoritesEditor);
        toggle(R.string.weberin_favorites, "weberin_show_favorites", config.dockRow);
        toggle(R.string.ws_show_brand, "weberin_show_brand", true);
        toggle(R.string.ws_show_hints, "weberin_show_hints", true);
        toggle(R.string.ws_show_news, "weberin_show_news", true);
        toggle(R.string.ws_show_xbox, "weberin_show_xbox", true);
        note(R.string.ws_channels_hint);
    }

    private void gridCards() {
        Config config = host.config();
        integerChoice(R.string.columns_label, "columns", new int[]{4, 5, 6, 7},
                preferences.contains("columns") ? config.columns : 5, false);
        integerChoice(R.string.ws_card_size, "weberin_card_scale", new int[]{90, 100, 110}, 100, true);
        toggle(R.string.ws_show_app_names, "weberin_show_app_names", true);
        toggle(R.string.ws_native_banners, "weberin_native_banners", false);
        integerChoice(R.string.dock_opacity_label, Config.DOCK_OPACITY_KEY, new int[]{8, 30, 50, 70, 100},
                preferences.contains(Config.DOCK_OPACITY_KEY) ? config.dockOpacity : 8, true);
        toggle(R.string.ws_surface_tint, "weberin_surface_tint", false);
    }

    private void weatherSettings() {
        toggle(R.string.weberin_weather_title, "weberin_show_weather", preferences.getBoolean("weberin_home_full", false));
        action(activity.getString(R.string.weberin_weather_city) + ": "
                + preferences.getString("weberin_weather_city", activity.getString(R.string.weberin_weather_default_city)),
                host::onWeatherCityPicker);
        stringChoice(R.string.ws_weather_position, "weberin_weather_position", new String[]{"right", "left"},
                new int[]{R.string.ws_right, R.string.ws_left}, "right");
        integerChoice(R.string.ws_weather_size, "weberin_weather_size", new int[]{80, 100, 120}, 100, true);
    }

    private void clockSettings() {
        toggle(R.string.weberin_clock, "weberin_show_clock", true);
        toggle(R.string.ws_show_date, "weberin_show_date", true);
        clockFormat();
        toggle(R.string.visibility_network, "visible_network", host.config().showNetworkPill);
    }

    private void effects() {
        toggle(R.string.ws_animations, "animations", host.config().animations);
        toggle(R.string.ws_surface_tint, "weberin_surface_tint", false);
        action(R.string.ws_light_mode, this::confirmLightMode);
    }

    private void memorySettings() {
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
    }

    private void storage() {
        try {
            StatFs disk = new StatFs(activity.getFilesDir().getPath());
            note(activity.getString(R.string.ws_storage_available, size(disk.getAvailableBytes()), size(disk.getTotalBytes())));
        } catch (IllegalArgumentException ignored) { }
        action(R.string.ws_clear_cache, this::confirmClearCache);
        action(R.string.ws_system_storage, () -> open(SystemSettings.intent(activity, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
    }

    private void deviceInfo() {
        note(activity.getString(R.string.ws_device_model, Build.MANUFACTURER, Build.MODEL));
        note(activity.getString(R.string.ws_android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        note(activity.getString(R.string.ws_display_resolution, metrics.widthPixels, metrics.heightPixels));
        memory();
    }

    private void connections() {
        ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        note(activity.getString(network != null && network.isConnected() ? R.string.ws_connected : R.string.weberin_network_offline));
        action(R.string.ws_system_network, () -> open(SystemSettings.intent(activity, Settings.ACTION_WIFI_SETTINGS)));
        action(R.string.ws_system_bluetooth, () -> open(SystemSettings.intent(activity, Settings.ACTION_BLUETOOTH_SETTINGS)));
    }

    private void appDetails(ResolveInfo app) {
        String name = host.appLabel(app);
        page(name, R.string.ws_app_actions, () -> {
            action(R.string.ws_open_app, () -> new WeberinLaunch(activity, host.config()).open(app));
            action(R.string.ws_manage_favorites, host::onFavoritesEditor);
            action(R.string.ws_system_app_info, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.activityInfo.packageName)), new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        });
    }

    private void appRow(ResolveInfo app) {
        FrameLayout container = new FrameLayout(activity);
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
        host.bindAppIcon(app, icon);
        place(row, icon, 12, 8, 58, 58);
        TextView name = text(host.appLabel(app), 22, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        place(row, name, 88, 0, 370, 74);
        TextView arrow = text("›", 30, 0xffaeb4bf, false);
        arrow.setGravity(Gravity.CENTER);
        place(row, arrow, 474, 0, 30, 74);
        row.setOnClickListener(view -> appDetails(app));
        place(container, row, 0, 0, 516, 74);
        View separator = new View(activity);
        separator.setBackgroundColor(0xff242426);
        place(container, separator, 40, 85, 476, 1);
        rows.addView(container, new LinearLayout.LayoutParams(516, 94));
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

    private TextView note(int resource) { return note(activity.getString(resource)); }

    private TextView note(String value) {
        TextView note = text(value, 18, 0xffa9b1be, false);
        note.setGravity(Gravity.TOP);
        note.setPadding(16, 4, 16, 20);
        rows.addView(note, new LinearLayout.LayoutParams(516, ViewGroup.LayoutParams.WRAP_CONTENT));
        return note;
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

    private void stringChoice(int label, String key, String[] values, int[] labels, String fallback) {
        TextView row = action("", () -> { });
        Runnable update = () -> {
            int index = java.util.Arrays.asList(values).indexOf(preferences.getString(key, fallback));
            row.setText(activity.getString(label) + ": " + activity.getString(labels[Math.max(0, index)]));
        };
        row.setOnClickListener(view -> {
            String[] choices = new String[labels.length];
            for (int i = 0; i < labels.length; i++) choices[i] = activity.getString(labels[i]);
            new AlertDialog.Builder(activity).setTitle(label).setSingleChoiceItems(choices,
                    java.util.Arrays.asList(values).indexOf(preferences.getString(key, fallback)), (dialog, which) -> {
                        preferences.edit().putString(key, values[which]).apply();
                        changed();
                        update.run();
                        dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        update.run();
    }

    private void specialAccess() {
        boolean allowed = WeberinDeviceControls.canWriteSystemSettings(activity);
        action(activity.getString(R.string.ws_write_settings) + ": " + activity.getString(
                allowed ? R.string.ws_access_granted : R.string.ws_access_not_granted), this::requestWriteSettings);
        note(R.string.ws_write_settings_reason);
        action(activity.getString(R.string.ws_home_key_helper) + ": " + activity.getString(
                helperEnabled("HomeKeyService") ? R.string.on : R.string.off), this::openAccessibility);
        action(activity.getString(R.string.ws_input_key_helper) + ": " + activity.getString(
                helperEnabled("InputKeyService") ? R.string.on : R.string.off), this::openAccessibility);
        note(R.string.ws_access_optional);
    }

    private boolean helperEnabled(String name) {
        AccessibilityManager manager = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) return false;
        try {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                android.content.pm.ServiceInfo service = info.getResolveInfo() == null ? null : info.getResolveInfo().serviceInfo;
                if (service != null && activity.getPackageName().equals(service.packageName)
                        && (service.name.equals(name) || service.name.endsWith("." + name))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void requestWriteSettings() {
        open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
    }

    private void openAccessibility() {
        open(SystemSettings.intent(activity, Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void brightness() {
        int window = preferences.getInt(WeberinDeviceControls.WINDOW_BRIGHTNESS, -1);
        TextView currentMode = note(activity.getString(R.string.ws_window_brightness_state,
                window < 0 ? activity.getString(R.string.clock_format_system) : window + "%"));
        range(R.string.ws_window_brightness, 20, 100, 5,
                () -> preferences.getInt(WeberinDeviceControls.WINDOW_BRIGHTNESS, 100) < 0 ? 100
                        : preferences.getInt(WeberinDeviceControls.WINDOW_BRIGHTNESS, 100), value -> {
                    preferences.edit().putInt(WeberinDeviceControls.WINDOW_BRIGHTNESS, value).apply();
                    WeberinDeviceControls.applyWindowBrightness(activity, preferences);
                    currentMode.setText(activity.getString(R.string.ws_window_brightness_state, value + "%"));
                    changed();
                    return true;
                });
        action(R.string.ws_window_brightness_system, () -> {
            preferences.edit().remove(WeberinDeviceControls.WINDOW_BRIGHTNESS).apply();
            WeberinDeviceControls.applyWindowBrightness(activity, preferences);
            changed();
            refresh();
        });
        note(R.string.ws_window_brightness_hint);
        int device = WeberinDeviceControls.systemBrightness(activity);
        if (device < 0) {
            note(R.string.ws_android_brightness_unavailable);
        } else if (WeberinDeviceControls.canWriteSystemSettings(activity)) {
            range(R.string.ws_android_brightness, 5, 100, 5,
                    () -> Math.round(WeberinDeviceControls.systemBrightness(activity) * 100f / 255), value ->
                            WeberinDeviceControls.writeSystemBrightness(activity, Math.round(value * 255f / 100)));
            if (WeberinDeviceControls.automaticBrightness(activity)) note(R.string.ws_auto_brightness_hint);
        } else {
            note(activity.getString(R.string.ws_android_brightness_value, Math.round(device * 100f / 255)));
            action(R.string.ws_allow_brightness, this::requestWriteSettings);
        }
        note(R.string.ws_android_brightness_hint);
        action(R.string.ws_tv_picture_external, () -> open(SystemSettings.displayIntent(activity)));
    }

    /** A remote-friendly native value bar; value changes are displayed only after the write succeeds. */
    private void range(int label, int min, int max, int step, java.util.function.IntSupplier read,
                       java.util.function.IntPredicate write) {
        FrameLayout control = new FrameLayout(activity);
        control.setId(View.generateViewId());
        control.setFocusableInTouchMode(true);
        control.setOnFocusChangeListener((view, focused) -> {
            control.setBackground(focused ? outline(0xff18181b, Color.WHITE, 12) : null);
            updateCategoryAppearance();
        });
        TextView name = text("", 23, Color.WHITE, false);
        name.setMaxLines(2);
        place(control, name, 16, 4, 484, 52);
        View track = new View(activity);
        track.setBackground(outline(0xff32343a, 0, 2));
        place(control, track, 16, 70, 484, 4);
        View progress = new View(activity);
        progress.setBackground(outline(0xffe9edf3, 0, 2));
        place(control, progress, 16, 70, 484, 4);
        Runnable update = () -> {
            int actual = read.getAsInt();
            name.setText(activity.getString(label) + ": " + actual + "%");
            ViewGroup.LayoutParams params = progress.getLayoutParams();
            params.width = Math.max(2, Math.min(484, Math.round(484f * (actual - min) / (max - min))));
            progress.setLayoutParams(params);
        };
        java.util.function.IntConsumer apply = value -> {
            if (!write.test(Math.max(min, Math.min(max, value))))
                Toast.makeText(activity, R.string.ws_setting_write_failed, Toast.LENGTH_LONG).show();
            update.run();
        };
        control.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN)
                apply.accept(read.getAsInt() + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step));
            return true;
        });
        control.setOnClickListener(view -> {
            int count = (max - min) / step + 1;
            String[] options = new String[count];
            for (int i = 0; i < count; i++) options[i] = (min + i * step) + "%";
            int selected = Math.max(0, Math.min(count - 1, Math.round((read.getAsInt() - min) / (float) step)));
            new AlertDialog.Builder(activity).setTitle(label).setSingleChoiceItems(options, selected, (dialog, which) -> {
                apply.accept(min + which * step);
                dialog.dismiss();
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(516, 94);
        params.bottomMargin = 8;
        rows.addView(control, params);
        controls.add(control);
        update.run();
        note(R.string.ws_value_control_hint);
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
            String selected = preferences.getString(Config.CLOCK_FORMAT_KEY, host.config().clockFormat);
            int index = java.util.Arrays.asList(values).indexOf(selected);
            row.setText(activity.getString(R.string.ws_clock_format) + ": " + activity.getString(labels[Math.max(0, index)]));
        };
        row.setOnClickListener(view -> {
            String[] choices = new String[3];
            for (int i = 0; i < choices.length; i++) choices[i] = activity.getString(labels[i]);
            int selected = java.util.Arrays.asList(values).indexOf(
                    preferences.getString(Config.CLOCK_FORMAT_KEY, host.config().clockFormat));
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
