package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Alternate home presentation. The original Home activity remains the system entry point. */
public final class WeberinActivity extends Activity {
    private static final String FAVORITES = "weberin_favorites";
    private SharedPreferences preferences;
    private LinearLayout content;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private int page;
    private String focusedPackage;
    private boolean restoringFocus;
    private android.graphics.Bitmap cachedWallpaper;
    private String cachedWallpaperKey;
    private boolean focusSelectedTab;
    private boolean initialFocus = true;
    private FrameLayout settingsOverlay;
    private Config activeConfig;
    private int settingsCategory;
    private final List<TextView> settingsCategoryRows = new ArrayList<>();
    private final java.util.concurrent.ExecutorService weatherExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout weatherPanel;
    private WeberinWeather.Reading weatherReading;
    private String weatherCityLoaded = "";
    private String weatherCityPending = "";
    private long weatherCheckedAt;
    private int weatherGeneration;
    private boolean weatherFailed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        page = state == null ? 0 : state.getInt("page", 0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        if (preferences != null && !preferences.getBoolean("weberin_layout", true)) finish();
        else if (preferences != null) { loadApps(); render(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page);
        super.onSaveInstanceState(out);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView button(String text, Runnable action) {
        TextView view = label(text, 17, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(8), dp(18), dp(8));
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        view.setOnFocusChangeListener((v, focused) -> {
            view.setTextColor(focused ? Color.BLACK : Color.WHITE);
            view.setBackground(shape(focused ? Color.WHITE : 0xff101010, focused ? Color.WHITE : 0xff353535, 14));
        });
        view.setBackground(shape(0xff101010, 0xff353535, 14));
        return view;
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (!info.activityInfo.packageName.equals(getPackageName()))
                    found.putIfAbsent(info.activityInfo.packageName, info);
            }
        }
        apps.clear();
        apps.addAll(found.values());
        Collections.sort(apps, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.loadLabel(pm).toString(), b.loadLabel(pm).toString()));
    }

    private void render() {
        settingsOverlay = null;
        weatherPanel = null;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Config config = Config.load(this);
        activeConfig = config;
        java.io.File wallpaper = config.wallpaper;
        if (wallpaper == null && !config.wallpapers.isEmpty()) wallpaper = config.wallpapers.get(0);
        String selected = preferences.getString("weberin_wallpaper", "01-windows-black-1080p.png");
        String wallpaperKey = "theme".equals(selected) && wallpaper != null
                ? "file:" + wallpaper.getAbsolutePath() + ":" + wallpaper.lastModified() + ":" + wallpaper.length()
                : selected;
        if (!wallpaperKey.equals(cachedWallpaperKey)) {
            cachedWallpaper = "theme".equals(selected) && wallpaper != null ? loadWallpaper(wallpaper)
                    : "none".equals(selected) || "theme".equals(selected) ? null : loadBundledWallpaper(selected);
            cachedWallpaperKey = wallpaperKey;
        }
        android.graphics.Bitmap bitmap = cachedWallpaper;
        if (bitmap != null) {
            ImageView background = new ImageView(this);
            background.setImageBitmap(bitmap);
            background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        }
        if (page == 0 && preferences.getBoolean("weberin_show_weather", true)) addWeatherPanel(root);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(40), dp(24), dp(40), dp(24));
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout header = new FrameLayout(this);
        TextView brand = label("WeberinTV", 21, Color.WHITE);
        android.text.SpannableString brandText = new android.text.SpannableString("WeberinTV");
        brandText.setSpan(new android.text.style.RelativeSizeSpan(0.48f), 7, 9, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        brand.setText(brandText);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new FrameLayout.LayoutParams(-2, dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(5), dp(4), dp(5), dp(4));
        tabs.setBackground(shape(0xd9000000, 0xff666666, 18));
        int[] labels = {R.string.weberin_home, R.string.weberin_apps, R.string.weberin_channels};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            TextView tab = label(getString(labels[i]), 15, Color.WHITE);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(14), 0, dp(14), 0);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> { page = target; focusedPackage = null; focusSelectedTab = true; render(); });
            tab.setOnFocusChangeListener((v, focused) -> {
                boolean active = page == target;
                tab.setTextColor(active ? Color.BLACK : 0xffeeeeee);
                tab.setBackground(active ? shape(Color.WHITE, 0, 18) : null);
                if (focused && page != target) {
                    tab.post(() -> {
                        if (!tab.hasFocus() || page == target) return;
                        page = target;
                        focusedPackage = null;
                        focusSelectedTab = true;
                        render();
                    });
                }
            });
            if (i == page) { tab.setTextColor(Color.BLACK); tab.setBackground(shape(Color.WHITE, 0, 18)); }
            if (i == page && focusSelectedTab) { tab.post(tab::requestFocus); focusSelectedTab = false; }
            tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(36)));
        }
        header.addView(tabs, new FrameLayout.LayoutParams(-2, dp(46), Gravity.CENTER));
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        if (config.showNetworkPill) {
            try {
                android.net.ConnectivityManager network = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkInfo info = network == null ? null : network.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    String networkName = info.getType() == android.net.ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : "Ethernet";
                    if (info.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                                getApplicationContext().getSystemService(WIFI_SERVICE);
                        android.net.wifi.WifiInfo wifiInfo = wifi == null ? null : wifi.getConnectionInfo();
                        String ssid = wifiInfo == null ? null : wifiInfo.getSSID();
                        if (ssid != null) {
                            ssid = ssid.replace("\"", "").trim();
                            if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) networkName += " · " + ssid;
                        }
                    }
                    TextView networkLabel = label(networkName, 10, 0xffb1b8c7);
                    networkLabel.setSingleLine(true);
                    networkLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    networkLabel.setMaxWidth(dp(130));
                    networkLabel.setPadding(0, 0, dp(12), 0);
                    status.addView(networkLabel);
                }
            } catch (SecurityException ignored) { }
        }
        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setScaleType(ImageView.ScaleType.CENTER);
        settings.setFocusable(true);
        settings.setContentDescription(getString(R.string.tv_settings));
        settings.setOnClickListener(v -> showSettings(root));
        settings.setOnFocusChangeListener((v, focused) -> settings.setBackground(
                focused ? shape(0x552f3035, 0xffeeeeee, 10) : null));
        status.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(44)));
        if (preferences.getBoolean("weberin_show_clock", true)) {
            LinearLayout clockBlock = new LinearLayout(this);
            clockBlock.setPadding(dp(14), 0, 0, 0);
            clockBlock.setOrientation(LinearLayout.VERTICAL);
            TextClock clock = new TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("h:mm a");
            clock.setTextColor(Color.WHITE);
            clock.setTextSize(21);
            clockBlock.addView(clock);
            TextClock date = new TextClock(this);
            date.setFormat12Hour("EEE, d MMMM");
            date.setFormat24Hour("EEE, d MMMM");
            date.setTextColor(0xffaeb6c5);
            date.setTextSize(10);
            clockBlock.addView(date);
            ClockFormat.apply(clockBlock, config.clockFormat);
            status.addView(clockBlock);
        }
        header.addView(status, new FrameLayout.LayoutParams(-2, dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        screen.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        screen.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (page == 2) {
            TextView empty = label(getString(R.string.weberin_channels_later), 22, 0xffb8bdc8);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        } else if (page == 0 && !preferences.getBoolean("weberin_show_favorites", true)) {
            // A deliberately empty home leaves the wallpaper visible. Navigation stays available.
        } else if (page == 0) {
            View space = new View(this);
            content.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(16), dp(8), dp(16), dp(8));
            int opacity = preferences.contains(Config.DOCK_OPACITY_KEY)
                    ? preferences.getInt(Config.DOCK_OPACITY_KEY, 60) : 60;
            opacity = Math.max(30, Math.min(100, opacity));
            panel.setBackground(shape((opacity * 255 / 100) << 24, 0x665f626b, 20));
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = label(getString(R.string.weberin_my_apps), 19, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1));
            TextView hint = label(getString(R.string.weberin_hold_hint), 11, 0xffb1b8c7);
            titleRow.addView(hint);
            panel.addView(titleRow);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            for (ResolveInfo app : favorites()) row.addView(tile(app));
            row.addView(allAppsTile());
            if (initialFocus && row.getChildCount() > 0) {
                initialFocus = false;
                View first = row.getChildAt(0);
                first.post(first::requestFocus);
            }
            scroll.addView(row);
            panel.addView(scroll);
            content.addView(panel);
        } else {
            addTitle(R.string.weberin_apps);
            ScrollView scroll = new ScrollView(this);
            scroll.setPadding(dp(12), dp(8), dp(12), dp(8));
            scroll.setBackground(shape(0x99000000, 0x665f626b, 20));
            GridLayout grid = new GridLayout(this);
            int columns = preferences.contains("columns") ? Config.load(this).columns : 5;
            grid.setColumnCount(Math.max(4, Math.min(7, columns)));
            for (ResolveInfo app : apps) grid.addView(tile(app));
            if (apps.isEmpty()) grid.addView(label(getString(R.string.weberin_no_apps), 18, Color.WHITE));
            scroll.addView(grid);
            content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private android.graphics.Bitmap loadBundledWallpaper(String name) {
        if (name.contains("/") || name.contains("..")) {
            return null;
        }
        try {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (java.io.InputStream input = getAssets().open("weberin/" + name)) {
                android.graphics.BitmapFactory.decodeStream(input, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight,
                    metrics.widthPixels, metrics.heightPixels);
            if (!WallpaperSize.accepted(options.outWidth / options.inSampleSize,
                    options.outHeight / options.inSampleSize)) return null;
            options.inJustDecodeBounds = false;
            try (java.io.InputStream input = getAssets().open("weberin/" + name)) {
                return android.graphics.BitmapFactory.decodeStream(input, null, options);
            }
        } catch (java.io.IOException | OutOfMemoryError failure) { return null; }
    }

    /** Decode only the chosen image, sampling it to the display rather than always allocating a 4K bitmap. */
    private android.graphics.Bitmap loadWallpaper(java.io.File file) {
        try {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight,
                    metrics.widthPixels, metrics.heightPixels);
            if (!WallpaperSize.accepted(options.outWidth / options.inSampleSize,
                    options.outHeight / options.inSampleSize)) return null;
            options.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void addTitle(int title) {
        TextView label = label(getString(title), 22, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.topMargin = dp(20);
        content.addView(label, params);
    }

    private List<ResolveInfo> favorites() {
        Set<String> saved = preferences.getStringSet(FAVORITES, null);
        List<ResolveInfo> result = new ArrayList<>();
        if (saved != null) {
            for (ResolveInfo app : apps) if (saved.contains(app.activityInfo.packageName)) result.add(app);
            return orderedFavorites(result);
        }
        // Prefer recognizable TV apps; never fill the dock with vendor services just because they sort first.
        String[] preferred = {"com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
                "ru.kinopoisk.tv", "ru.ivi.client", "ru.okko"};
        for (String pkg : preferred) {
            for (ResolveInfo app : apps) {
                if (pkg.equals(app.activityInfo.packageName) && result.size() < 5) result.add(app);
            }
        }
        for (ResolveInfo app : apps) {
            if (result.size() >= 5) break;
            int flags = app.activityInfo.applicationInfo.flags;
            if ((flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 && !result.contains(app)) result.add(app);
        }
        return orderedFavorites(result);
    }

    private List<ResolveInfo> orderedFavorites(List<ResolveInfo> result) {
        List<String> order = new ArrayList<>();
        try {
            org.json.JSONArray json = new org.json.JSONArray(preferences.getString("weberin_favorites_order", "[]"));
            for (int i = 0; i < json.length(); i++) order.add(json.getString(i));
        } catch (org.json.JSONException ignored) { }
        java.util.Collections.sort(result, (a, b) -> {
            int left = order.indexOf(a.activityInfo.packageName);
            int right = order.indexOf(b.activityInfo.packageName);
            return Integer.compare(left < 0 ? Integer.MAX_VALUE : left, right < 0 ? Integer.MAX_VALUE : right);
        });
        return result;
    }

    private void showTileMenu(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        List<ResolveInfo> current = favorites();
        int position = -1;
        for (int i = 0; i < current.size(); i++) {
            if (pkg.equals(current.get(i).activityInfo.packageName)) { position = i; break; }
        }
        final int index = position;
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add(getString(index >= 0 ? R.string.library_remove_shortcut : R.string.library_add_home));
        actions.add(() -> {
            Set<String> next = new HashSet<>();
            for (ResolveInfo favorite : current) next.add(favorite.activityInfo.packageName);
            if (index >= 0) next.remove(pkg); else next.add(pkg);
            preferences.edit().putStringSet(FAVORITES, next).apply();
            if (page == 0) render();
        });
        if (index > 0) {
            choices.add(getString(R.string.weberin_move_left));
            actions.add(() -> moveFavorite(current, index, index - 1));
        }
        if (index >= 0 && index < current.size() - 1) {
            choices.add(getString(R.string.weberin_move_right));
            actions.add(() -> moveFavorite(current, index, index + 1));
        }
        choices.add(getString(R.string.library_app_info));
        actions.add(() -> openSettingsTarget(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + pkg))));
        new android.app.AlertDialog.Builder(this).setTitle(app.loadLabel(getPackageManager()))
                .setItems(choices.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void moveFavorite(List<ResolveInfo> current, int from, int to) {
        java.util.Collections.swap(current, from, to);
        org.json.JSONArray order = new org.json.JSONArray();
        for (ResolveInfo favorite : current) order.put(favorite.activityInfo.packageName);
        preferences.edit().putString("weberin_favorites_order", order.toString()).apply();
        render();
    }

    @Override public void onBackPressed() {
        if (settingsOverlay != null) {
            settingsOverlay = null;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void showSettings(FrameLayout root) {
        FrameLayout overlay = new FrameLayout(this);
        settingsOverlay = overlay;
        overlay.setBackgroundColor(0x99000000);
        overlay.setFocusable(true);
        overlay.setOnKeyListener((v, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                settingsOverlay = null;
                render();
                return true;
            }
            return false;
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(12), dp(20), dp(12), dp(16));
        sidebar.setBackground(shape(0xff09090a, 0xff777777, 20));
        FrameLayout.LayoutParams sideParams = new FrameLayout.LayoutParams(dp(165), -1, Gravity.END);
        sideParams.setMargins(0, dp(18), dp(18), dp(18));
        overlay.addView(sidebar, sideParams);
        LinearLayout sidebarHeader = new LinearLayout(this);
        sidebarHeader.setGravity(Gravity.CENTER_VERTICAL);
        sidebarHeader.setPadding(dp(10), 0, dp(4), 0);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setScaleType(ImageView.ScaleType.CENTER);
        sidebarHeader.addView(gear, new LinearLayout.LayoutParams(dp(28), dp(40)));
        View divider = new View(this);
        divider.setBackgroundColor(0xff29292b);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(32));
        dividerParams.setMargins(dp(8), 0, dp(10), 0);
        sidebarHeader.addView(divider, dividerParams);
        LinearLayout sidebarClock = new LinearLayout(this);
        sidebarClock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm"); time.setFormat12Hour("h:mm a");
        time.setTextColor(Color.WHITE); time.setTextSize(22);
        sidebarClock.addView(time);
        TextClock sidebarDate = new TextClock(this);
        sidebarDate.setFormat24Hour("EEEE, d MMMM"); sidebarDate.setFormat12Hour("EEEE, d MMMM");
        sidebarDate.setTextColor(0xffbfc6d3); sidebarDate.setTextSize(9);
        sidebarClock.addView(sidebarDate);
        ClockFormat.apply(sidebarClock, Config.load(this).clockFormat);
        sidebarHeader.addView(sidebarClock, new LinearLayout.LayoutParams(0, -2, 1));
        sidebar.addView(sidebarHeader, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(20), dp(18), dp(20), dp(18));
        details.setBackground(shape(0xff09090a, 0xff777777, 20));
        FrameLayout.LayoutParams detailParams = new FrameLayout.LayoutParams(dp(330), -1, Gravity.END);
        detailParams.setMargins(0, dp(18), dp(200), dp(18));
        overlay.addView(details, detailParams);
        settingsCategoryRows.clear();
        settingsCategory = 0;
        int[] categories = {R.string.weberin_settings_apps, R.string.weberin_settings_system,
                R.string.weberin_settings_display, R.string.weberin_settings_sound,
                R.string.weberin_settings_themes, R.string.weberin_settings_other};
        for (int i = 0; i < categories.length; i++) {
            final int category = i;
            TextView item = label(getString(categories[i]), 16, Color.WHITE);
            item.setGravity(Gravity.CENTER);
            item.setFocusable(true);
            item.setBackground(shape(0x00111111, 0, 9));
            item.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    settingsCategory = category;
                    fillSettings(details, category);
                }
                for (int j = 0; j < settingsCategoryRows.size(); j++) {
                    TextView choice = settingsCategoryRows.get(j);
                    choice.setBackground(shape(0x00111111, j == settingsCategory ? Color.WHITE : 0, 9));
                }
            });
            item.setOnClickListener(v -> fillSettings(details, category));
            settingsCategoryRows.add(item);
            sidebar.addView(item, new LinearLayout.LayoutParams(-1, dp(42)));
            if (i == 0) item.post(item::requestFocus);
        }
        fillSettings(details, 0);
    }

    private void fillSettings(LinearLayout details, int category) {
        details.removeAllViews();
        int[] categories = {R.string.weberin_settings_apps, R.string.weberin_settings_system,
                R.string.weberin_settings_display, R.string.weberin_settings_sound,
                R.string.weberin_settings_themes, R.string.weberin_settings_other};
        TextView title = label(getString(categories[category]), 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        details.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        details.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (category == 0) {
            for (ResolveInfo app : apps) {
                TextView row = button(app.loadLabel(getPackageManager()).toString() + "  ›", () -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + app.activityInfo.packageName));
                    openSettingsTarget(intent);
                });
                rows.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
            }
        } else if (category == 1) {
            addSettingsRow(rows, R.string.tv_settings, new Intent(android.provider.Settings.ACTION_SETTINGS));
            addSettingsRow(rows, R.string.home_settings, new Intent(this, HomeSettingsActivity.class));
        } else if (category == 2) {
            addToggleRow(rows, R.string.weberin_clock, "weberin_show_clock");
            addToggleRow(rows, R.string.weberin_favorites, "weberin_show_favorites");
            addToggleRow(rows, R.string.weberin_weather_title, "weberin_show_weather");
            TextView weatherCityChoice = button(getString(R.string.weberin_weather_city), this::showWeatherCityPicker);
            rows.addView(weatherCityChoice, new LinearLayout.LayoutParams(-1, dp(48)));
            addStockToggleRow(rows, R.string.visibility_network, "visible_network", Config.load(this).showNetworkPill);
            addIntChoiceRow(rows, R.string.columns_label, "columns", new int[]{4, 5, 6, 7}, Config.load(this).columns);
            addIntChoiceRow(rows, R.string.dock_opacity_label, Config.DOCK_OPACITY_KEY, new int[]{100, 70, 50, 30}, Config.load(this).dockOpacity);
            addClockChoiceRow(rows);
            addStockToggleRow(rows, R.string.animations_state, "animations", Config.load(this).animations);
            addSettingsRow(rows, R.string.weberin_settings_display, new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
            addSettingsRow(rows, R.string.launcher_settings, new Intent(this, LauncherSettingsActivity.class));
        } else if (category == 3) {
            addSettingsRow(rows, R.string.weberin_settings_sound, new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
        } else {
            if (category == 4) {
                TextView wallpaper = button(getString(R.string.weberin_wallpaper) + "  ›", this::showBundledWallpaperPicker);
                rows.addView(wallpaper, new LinearLayout.LayoutParams(-1, dp(48)));
                TextView original = button(getString(R.string.layout_original) + "  ›", () -> {
                    preferences.edit().putBoolean("weberin_layout", false).apply();
                    finish();
                });
                rows.addView(original, new LinearLayout.LayoutParams(-1, dp(48)));
            }
            addSettingsRow(rows, R.string.launcher_settings, new Intent(this, LauncherSettingsActivity.class));
            if (category == 5) addSettingsRow(rows, R.string.tv_settings, new Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }

    private void addToggleRow(LinearLayout rows, int text, String key) {
        TextView row = button("", () -> {});
        Runnable refresh = () -> row.setText(getString(text) + ": " +
                getString(preferences.getBoolean(key, true) ? R.string.on : R.string.off));
        row.setOnClickListener(v -> {
            preferences.edit().putBoolean(key, !preferences.getBoolean(key, true)).apply();
            refresh.run();
        });
        refresh.run();
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void showWeatherCityPicker() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city)));
        new android.app.AlertDialog.Builder(this).setTitle(R.string.weberin_weather_city).setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String city = input.getText().toString().trim();
                    if (city.length() < 2 || city.length() > 80) {
                        Toast.makeText(this, R.string.weberin_weather_invalid_city, Toast.LENGTH_LONG).show();
                        return;
                    }
                    preferences.edit().putString("weberin_weather_city", city).apply();
                    weatherCheckedAt = 0;
                    weatherCityLoaded = "";
                    weatherReading = null;
                    weatherFailed = false;
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addWeatherPanel(FrameLayout root) {
        weatherPanel = new LinearLayout(this);
        weatherPanel.setOrientation(LinearLayout.VERTICAL);
        weatherPanel.setGravity(Gravity.CENTER);
        weatherPanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(190), dp(220), Gravity.END | Gravity.CENTER_VERTICAL);
        params.setMargins(0, 0, dp(44), dp(64));
        root.addView(weatherPanel, params);
        updateWeatherPanel();
        requestWeatherIfNeeded();
    }

    private void weatherLine(String value, int size, int color, boolean bold) {
        TextView text = label(value, size, color);
        text.setGravity(Gravity.CENTER);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        weatherPanel.addView(text, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateWeatherPanel() {
        if (weatherPanel == null) return;
        weatherPanel.removeAllViews();
        String requestedCity = preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city));
        WeberinWeather.Reading reading = requestedCity.equals(weatherCityLoaded) ? weatherReading : null;
        weatherLine(reading == null ? requestedCity : reading.city, 17, Color.WHITE, true);
        if (reading == null) {
            weatherLine(getString(weatherFailed && requestedCity.equals(weatherCityLoaded)
                    ? R.string.weberin_weather_unavailable : R.string.weberin_weather_loading), 11, 0xffb1b8c7, false);
            return;
        }
        weatherLine(getString(R.string.weberin_weather_humidity, reading.humidity), 11, 0xffb1b8c7, false);
        int condition = WeberinWeather.condition(reading.code);
        String[] icons = {"☀", "☁", "≋", "☂", "❄", "ϟ"};
        int[] descriptions = {R.string.weberin_weather_clear, R.string.weberin_weather_cloudy,
                R.string.weberin_weather_fog, R.string.weberin_weather_rain,
                R.string.weberin_weather_snow, R.string.weberin_weather_storm};
        weatherLine(icons[condition], 28, 0xffd6e6ef, false);
        weatherLine(String.format(java.util.Locale.getDefault(), "%d°", reading.temperature), 40, Color.WHITE, false);
        weatherLine(getString(descriptions[condition]), 14, Color.WHITE, false);
        weatherLine(getString(R.string.weberin_weather_range, reading.low, reading.high), 10, 0xffb1b8c7, false);
        weatherLine("Open-Meteo.com", 8, 0xff969eac, false);
    }

    private void requestWeatherIfNeeded() {
        String city = preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city));
        long now = android.os.SystemClock.elapsedRealtime();
        long interval = weatherFailed ? 5 * 60_000L : 30 * 60_000L;
        if (city.equals(weatherCityPending) || city.equals(weatherCityLoaded) && now - weatherCheckedAt < interval) return;
        weatherCityPending = city;
        int generation = ++weatherGeneration;
        String language = getResources().getConfiguration().locale.getLanguage();
        weatherExecutor.execute(() -> {
            WeberinWeather.Reading reading = null;
            try { reading = WeberinWeather.fetch(city, language); }
            catch (java.io.IOException | org.json.JSONException ignored) { }
            final WeberinWeather.Reading result = reading;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != weatherGeneration) return;
                weatherCityPending = "";
                weatherCityLoaded = city;
                weatherReading = result;
                weatherFailed = result == null;
                weatherCheckedAt = android.os.SystemClock.elapsedRealtime();
                updateWeatherPanel();
            });
        });
    }

    @Override protected void onDestroy() {
        weatherGeneration++;
        weatherExecutor.shutdownNow();
        super.onDestroy();
    }

    private void markSettingsChanged() {
        preferences.edit().putLong(Config.SETTINGS_REVISION_KEY,
                preferences.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void addStockToggleRow(LinearLayout rows, int text, String key, boolean fallback) {
        TextView row = button("", () -> {});
        Runnable refresh = () -> {
            String state = getString(preferences.getBoolean(key, fallback) ? R.string.on : R.string.off);
            row.setText(text == R.string.animations_state ? getString(text, state) : getString(text) + ": " + state);
        };
        row.setOnClickListener(v -> {
            boolean value = !preferences.getBoolean(key, fallback);
            android.content.SharedPreferences.Editor edit = preferences.edit().putBoolean(key, value);
            if ("animations".equals(key) && !value) edit.putBoolean("advanced_effects", false);
            edit.apply();
            markSettingsChanged();
            refresh.run();
        });
        refresh.run();
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addIntChoiceRow(LinearLayout rows, int labelId, String key, int[] values, int fallback) {
        TextView row = button("", () -> {});
        Runnable refresh = () -> row.setText(getString(labelId) + ": " + preferences.getInt(key, fallback));
        row.setOnClickListener(v -> {
            String[] labels = new String[values.length];
            int selected = -1;
            int current = preferences.getInt(key, fallback);
            for (int i = 0; i < values.length; i++) {
                labels[i] = String.valueOf(values[i]);
                if (values[i] == current) selected = i;
            }
            new android.app.AlertDialog.Builder(this).setTitle(labelId)
                    .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                        preferences.edit().putInt(key, values[which]).apply();
                        markSettingsChanged();
                        refresh.run();
                        dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        refresh.run();
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void addClockChoiceRow(LinearLayout rows) {
        TextView row = button(getString(R.string.dream_name), () -> {
            String[] values = {"system", "12", "24"};
            String[] labels = {getString(R.string.clock_format_system), getString(R.string.clock_format_12), getString(R.string.clock_format_24)};
            String selected = preferences.getString(Config.CLOCK_FORMAT_KEY, Config.load(this).clockFormat);
            int index = java.util.Arrays.asList(values).indexOf(selected);
            new android.app.AlertDialog.Builder(this).setTitle(R.string.dream_name)
                    .setSingleChoiceItems(labels, index, (dialog, which) -> {
                        preferences.edit().putString(Config.CLOCK_FORMAT_KEY, values[which]).apply();
                        markSettingsChanged();
                        dialog.dismiss();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void showBundledWallpaperPicker() {
        try {
            String[] files = getAssets().list("weberin");
            if (files == null) return;
            List<String> pictures = new ArrayList<>();
            pictures.add("theme"); pictures.add("none");
            for (String file : files) if (file.endsWith(".jpg") || file.endsWith(".png")) pictures.add(file);
            String[] names = pictures.toArray(new String[0]);
            names[0] = getString(R.string.wallpaper_theme_default);
            names[1] = getString(R.string.wallpaper_none);
            int selected = pictures.indexOf(preferences.getString("weberin_wallpaper", "01-windows-black-1080p.png"));
            new android.app.AlertDialog.Builder(this).setTitle(R.string.weberin_wallpaper)
                    .setSingleChoiceItems(names, selected, (dialog, which) -> {
                        preferences.edit().putString("weberin_wallpaper", pictures.get(which)).apply();
                        dialog.dismiss();
                        render();
                    }).setNegativeButton(android.R.string.cancel, null).show();
        } catch (java.io.IOException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void addSettingsRow(LinearLayout rows, int text, Intent intent) {
        TextView row = button(getString(text) + "  ›", () -> openSettingsTarget(intent));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(4);
        rows.addView(row, params);
    }

    private void openSettingsTarget(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private View allAppsTile() {
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int tileColumns = page == 1 && preferences.contains("columns")
                ? Math.max(4, Math.min(7, activeConfig.columns)) : (page == 1 ? 5 : 6);
        int tileWidth = Math.max(64, (screenDp - 108 - tileColumns * 12) / tileColumns);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(tileWidth), -2);
        params.setMargins(dp(4), dp(5), dp(8), dp(6));
        holder.setLayoutParams(params);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setBackground(shape(0xb31a1a1c, 0x66484a50, 12));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_apps);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        holder.addView(card, new LinearLayout.LayoutParams(-1, dp(Math.round(tileWidth * 0.57f))));
        TextView caption = label(getString(R.string.weberin_all_short), 13, 0xffbfc6d3);
        caption.setGravity(Gravity.CENTER);
        holder.addView(caption, new LinearLayout.LayoutParams(-1, dp(26)));
        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(shape(focused ? 0xff303135 : 0xb31a1a1c, focused ? Color.WHITE : 0x66484a50, 12));
            caption.setTextColor(focused ? Color.WHITE : 0xffbfc6d3);
        });
        card.setOnClickListener(v -> { page = 1; focusedPackage = null; focusSelectedTab = true; render(); });
        return holder;
    }

    private View tile(ResolveInfo app) {
        String name = app.loadLabel(getPackageManager()).toString();
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int tileColumns = page == 1 && preferences.contains("columns")
                ? Math.max(4, Math.min(7, activeConfig.columns)) : (page == 1 ? 5 : 6);
        int tileWidth = Math.max(64, (screenDp - 108 - tileColumns * 12) / tileColumns);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(tileWidth), -2);
        params.setMargins(dp(4), dp(5), dp(8), dp(6));
        holder.setLayoutParams(params);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setBackground(shape(0xb31a1a1c, 0x66484a50, 12));
        card.setClipToOutline(true);
        holder.addView(card, new LinearLayout.LayoutParams(-1, dp(Math.round(tileWidth * 0.57f))));
        android.graphics.drawable.Drawable banner = app.activityInfo.loadBanner(getPackageManager());
        ImageView art = new ImageView(this);
        if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        } else {
            TextView appName = label(name, 17, Color.WHITE);
            appName.setGravity(Gravity.CENTER);
            appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            appName.setPadding(dp(6), 0, dp(6), 0);
            card.addView(appName, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView caption = label(name, 13, 0xffbfc6d3);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.addView(caption, new LinearLayout.LayoutParams(-1, dp(26)));
        card.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.activityInfo.packageName, app.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show(); }
        });
        card.setOnLongClickListener(v -> {
            showTileMenu(app);
            return true;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(shape(focused ? 0xe51a1a1c : 0xb31a1a1c, focused ? Color.WHITE : 0x66484a50, 12));
            card.setForeground(shape(Color.TRANSPARENT, focused ? Color.WHITE : 0, 12));
            float scale = focused && activeConfig.animations ? 1.03f : 1f;
            card.setScaleX(scale);
            card.setScaleY(scale);
            caption.setTextColor(focused ? Color.WHITE : 0xffbfc6d3);
            if (focused && !restoringFocus) focusedPackage = app.activityInfo.packageName;
        });
        if (app.activityInfo.packageName.equals(focusedPackage))
            card.post(() -> { restoringFocus = true; card.requestFocus(); restoringFocus = false; });
        return holder;
    }
}
