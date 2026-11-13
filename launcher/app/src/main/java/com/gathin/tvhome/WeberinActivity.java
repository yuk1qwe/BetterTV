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
        TextView brand = label("Weberinᵀⱽ", 21, Color.WHITE);
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
        TextView settings = button("⚙", () -> startActivity(new Intent(this, LauncherSettingsActivity.class)));
        status.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(44)));
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
            TextView title = label(getString(R.string.weberin_my_apps), 19, Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            panel.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            for (ResolveInfo app : favorites()) row.addView(tile(app));
            TextView all = button(getString(R.string.weberin_all_apps), () -> { page = 1; focusedPackage = null; focusSelectedTab = true; render(); });
            LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(dp(150), dp(88));
            allParams.setMargins(dp(4), dp(6), dp(4), dp(28));
            row.addView(all, allParams);
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

    private View tile(ResolveInfo app) {
        String name = app.loadLabel(getPackageManager()).toString();
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int tileWidth = Math.max(64, Math.min(160, (screenDp - 170) / 5));
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(tileWidth), -2);
        params.setMargins(dp(4), dp(5), dp(8), dp(6));
        holder.setLayoutParams(params);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setBackground(shape(0xb31a1a1c, 0x66484a50, 12));
        holder.addView(card, new LinearLayout.LayoutParams(-1, dp(88)));
        android.graphics.drawable.Drawable banner = app.activityInfo.loadBanner(getPackageManager());
        ImageView art = new ImageView(this);
        if (banner != null) {
            art.setImageDrawable(banner);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        } else {
            art.setImageDrawable(app.loadIcon(getPackageManager()));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(art, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
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
