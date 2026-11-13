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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Config config = Config.load(this);
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
                boolean active = focused || page == target;
                tab.setTextColor(active ? Color.BLACK : 0xffeeeeee);
                tab.setBackground(active ? shape(Color.WHITE, 0, 18) : null);
            });
            if (i == page) { tab.setTextColor(Color.BLACK); tab.setBackground(shape(Color.WHITE, 0, 18)); }
            if (i == page && focusSelectedTab) { tab.post(tab::requestFocus); focusSelectedTab = false; }
            tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(36)));
        }
        header.addView(tabs, new FrameLayout.LayoutParams(-2, dp(46), Gravity.CENTER));
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
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
            panel.setBackground(shape(0x99000000, 0x665f626b, 20));
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
            grid.setColumnCount(5);
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
            return result;
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
        return result;
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
        sidebar.setBackground(shape(0xf509090a, 0xff777777, 20));
        FrameLayout.LayoutParams sideParams = new FrameLayout.LayoutParams(dp(165), -1, Gravity.END);
        sideParams.setMargins(0, dp(18), dp(18), dp(18));
        overlay.addView(sidebar, sideParams);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm"); time.setFormat12Hour("h:mm a");
        time.setTextColor(Color.WHITE); time.setTextSize(22);
        time.setGravity(Gravity.CENTER);
        sidebar.addView(time, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(20), dp(18), dp(20), dp(18));
        details.setBackground(shape(0xf509090a, 0xff777777, 20));
        FrameLayout.LayoutParams detailParams = new FrameLayout.LayoutParams(dp(330), -1, Gravity.END);
        detailParams.setMargins(0, dp(18), dp(200), dp(18));
        overlay.addView(details, detailParams);
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
                item.setBackground(shape(0x00111111, focused ? Color.WHITE : 0, 9));
                if (focused) fillSettings(details, category);
            });
            item.setOnClickListener(v -> fillSettings(details, category));
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
        int tileWidth = Math.max(64, (screenDp - 180) / 6);
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
        int tileWidth = Math.max(64, (screenDp - 180) / 6);
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
            Set<String> saved = preferences.getStringSet(FAVORITES, null);
            Set<String> next = saved == null ? new HashSet<>() : new HashSet<>(saved);
            if (saved == null) for (ResolveInfo favorite : favorites()) next.add(favorite.activityInfo.packageName);
            String pkg = app.activityInfo.packageName;
            boolean added = !next.remove(pkg);
            if (added) next.add(pkg);
            preferences.edit().putStringSet(FAVORITES, next).apply();
            Toast.makeText(this, added ? R.string.weberin_added : R.string.weberin_removed, Toast.LENGTH_SHORT).show();
            if (page == 0) render();
            return true;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(shape(focused ? 0xe51a1a1c : 0xb31a1a1c, focused ? Color.WHITE : 0x66484a50, 12));
            card.setForeground(shape(Color.TRANSPARENT, focused ? Color.WHITE : 0, 12));
            card.setScaleX(focused ? 1.03f : 1f);
            card.setScaleY(focused ? 1.03f : 1f);
            caption.setTextColor(focused ? Color.WHITE : 0xffbfc6d3);
            if (focused && !restoringFocus) focusedPackage = app.activityInfo.packageName;
        });
        if (app.activityInfo.packageName.equals(focusedPackage))
            card.post(() -> { restoringFocus = true; card.requestFocus(); restoringFocus = false; });
        return holder;
    }
}
