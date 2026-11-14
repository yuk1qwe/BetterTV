package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native alternate presentation; the original launcher and its settings remain available. */
public final class WeberinActivity extends Activity implements WeberinSettings.Host, WeberinStartup.Host {
    private static final String FAVORITES = "weberin_favorites", FAVORITE_ORDER = "weberin_favorites_order";
    private static final String DEFAULT_WALLPAPER = "01-windows-black-1080p.png";
    private SharedPreferences preferences;
    private Config config;
    private WeberinLaunch launch;
    private WeberinStartup startup;
    private FrameLayout scene, pageContent, weatherPanel, overlay;
    private WeberinSettings settingsPanel;
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final TextView[] tabs = new TextView[3];
    private ImageView settingsButton;
    private View homeAction;
    private int page, focusedTab = -1;
    private String focusedPackage;
    private Bitmap wallpaper;
    private String wallpaperKey;
    private final Map<String, Drawable> banners = new LinkedHashMap<String, Drawable>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable> entry) { return size() > 32; }
    };
    private final ExecutorService weatherExecutor = Executors.newSingleThreadExecutor();
    private WeberinWeather.Reading weatherReading;
    private String weatherCityLoaded = "", weatherCityPending = "";
    private long weatherCheckedAt;
    private int weatherGeneration;
    private boolean weatherFailed;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(LauncherLocale.wrap(base)); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        page = state == null ? 0 : Math.max(0, Math.min(2, state.getInt("page", 0)));
        focusedPackage = state == null ? null : state.getString("focused_package");
        startup = new WeberinStartup(this, this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!preferences.getBoolean("weberin_layout", true)) { finish(); return; }
        config = Config.load(this); launch = new WeberinLaunch(this, config); loadApps();
        if (settingsPanel == null) render(); else settingsPanel.refresh();
        startup.resume(config);
    }
    @Override protected void onPause() { startup.pause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page); out.putString("focused_package", focusedPackage); super.onSaveInstanceState(out);
    }
    private TextView text(CharSequence value, int size, int color, boolean bold) { return WeberinUi.text(this, value, size, color, bold); }
    private void put(FrameLayout parent, View view, int x, int y, int w, int h) { WeberinUi.put(parent, view, x, y, w, h); }

    private void loadApps() {
        Map<String, ResolveInfo> found = new LinkedHashMap<>(); PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo app : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (app.activityInfo == null || !app.activityInfo.exported || !app.activityInfo.enabled
                        || app.activityInfo.packageName.equals(getPackageName())
                        || config.libraryExcludedApps.contains(app.activityInfo.packageName)) continue;
                found.putIfAbsent(app.activityInfo.packageName, app);
            }
        }
        for (String pkg : config.libraryApps) {
            Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
            ResolveInfo app = intent == null ? null : pm.resolveActivity(intent, 0);
            if (app != null && app.activityInfo != null && app.activityInfo.exported
                    && !config.libraryExcludedApps.contains(pkg) && !pkg.equals(getPackageName())) found.putIfAbsent(pkg, app);
        }
        apps.clear(); apps.addAll(found.values());
        java.text.Collator collator = java.text.Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(launch.label(a), launch.label(b)));
    }

    private void render() {
        int tabToRestore = focusedTab;
        settingsPanel = null; overlay = null; scene = new FrameLayout(this);
        scene.setClipChildren(false); scene.setBackgroundColor(Color.BLACK);
        scene.setFocusableInTouchMode(true); scene.requestFocus(); addWallpaper();
        pageContent = new FrameLayout(this); pageContent.setClipChildren(false);
        put(scene, pageContent, 0, 0, 1920, 1080); addHeader();
        setContentView(new WeberinUi.Viewport(this, scene)); populatePage();
        scene.post(() -> {
            if (tabToRestore >= 0) tabs[tabToRestore].requestFocus();
            else if (!restoreCardFocus()) { if (!cards.isEmpty()) cards.get(0).requestFocus(); else tabs[page].requestFocus(); }
        });
    }

    private void addWallpaper() {
        String selected = preferences.getString("weberin_wallpaper", DEFAULT_WALLPAPER);
        File file = config.wallpaper;
        if (file == null && !config.wallpapers.isEmpty()) file = config.wallpapers.get(0);
        String key = "theme".equals(selected) && file != null ? file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length() : selected;
        if (!key.equals(wallpaperKey)) { wallpaper = decodeWallpaper(selected, file); wallpaperKey = key; }
        if (wallpaper != null) {
            ImageView image = new ImageView(this); image.setImageBitmap(wallpaper); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            put(scene, image, 0, 0, 1920, 1080);
        }
    }
    private Bitmap decodeWallpaper(String selected, File file) {
        if (selected == null || "none".equals(selected) || "theme".equals(selected) && file == null) return null;
        if (!"theme".equals(selected) && (selected.contains("/") || selected.contains(".."))) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            if ("theme".equals(selected)) BitmapFactory.decodeFile(file.getPath(), options);
            else try (InputStream input = getAssets().open("weberin/" + selected)) { BitmapFactory.decodeStream(input, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight, metrics.widthPixels, metrics.heightPixels);
            if (!WallpaperSize.accepted(options.outWidth / options.inSampleSize, options.outHeight / options.inSampleSize)) return null;
            options.inJustDecodeBounds = false;
            if ("theme".equals(selected)) return BitmapFactory.decodeFile(file.getPath(), options);
            try (InputStream input = getAssets().open("weberin/" + selected)) { return BitmapFactory.decodeStream(input, null, options); }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    private void addHeader() {
        TextView brand = text("Weberin", 34, WeberinUi.WHITE, true);
        brand.setTypeface(WeberinUi.font(this, 700)); brand.setLetterSpacing(-.045f);
        if (preferences.getBoolean("weberin_show_brand", true)) {
            put(scene, brand, 96, 67, 133, 42); put(scene, text("TV", 14, WeberinUi.WHITE, true), 229, 86, 25, 20);
        }
        FrameLayout capsule = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff0d0d0d, 0xff030303});
        shape.setCornerRadius(32); shape.setStroke(1, 0xff858585); capsule.setBackground(shape);
        put(scene, capsule, 688, 59, 575, 69);
        int[] names = {R.string.weberin_home, R.string.weberin_apps, R.string.weberin_channels};
        int[] x = {13, 182, 402}, widths = {132, 185, 160};
        for (int i = 0; i < 3; i++) {
            final int target = i; TextView tab = text(getString(names[i]), 22, WeberinUi.WHITE, false);
            tab.setGravity(Gravity.CENTER); tab.setFocusableInTouchMode(true); tab.setId(View.generateViewId());
            tab.setOnClickListener(v -> selectPage(target));
            tab.setOnFocusChangeListener((v, focus) -> { if (focus) { focusedTab = target; selectPage(target); } });
            tab.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) { tabs[Math.max(0, target - 1)].requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (target < 2) tabs[target + 1].requestFocus(); else settingsButton.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!restoreCardFocus() && !cards.isEmpty()) cards.get(0).requestFocus(); return true; }
                return false;
            });
            tabs[i] = tab; put(capsule, tab, x[i], 9, widths[i], 52);
        }
        if (config.showNetworkPill) {
            TextView network = text(networkLabel(), 14, WeberinUi.MUTED, false);
            network.setSingleLine(); network.setEllipsize(TextUtils.TruncateAt.END); network.setFocusable(true);
            network.setContentDescription(getString(R.string.visibility_network));
            network.setOnClickListener(v -> openSettingsTarget(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            network.setOnFocusChangeListener((v, focus) -> network.setBackground(focus ? WeberinUi.round(0x22111111, 0xffcdd3dc, 8) : null));
            put(scene, network, 1364, 76, 228, 28);
        }
        settingsButton = new ImageView(this); settingsButton.setFocusableInTouchMode(true); settingsButton.setId(View.generateViewId());
        settingsButton.setImageResource(R.drawable.ic_settings); settingsButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settingsButton.setPadding(14, 14, 14, 14); settingsButton.setContentDescription(getString(R.string.tv_settings));
        settingsButton.setOnFocusChangeListener((v, focus) -> { if (focus) focusedTab = -1;
            settingsButton.setBackground(focus ? WeberinUi.round(0x33151515, 0xffe5e9ee, 12) : null); });
        settingsButton.setOnClickListener(v -> showSettings());
        settingsButton.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { tabs[2].requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!restoreCardFocus() && !cards.isEmpty()) cards.get(cards.size() - 1).requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        put(scene, settingsButton, 1616, 64, 48, 48);
        if (preferences.getBoolean("weberin_show_clock", true)) {
            View divider = new View(this); divider.setBackgroundColor(0xff141414); put(scene, divider, 1676, 64, 1, 48);
            TextClock clock = new TextClock(this); clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32);
            clock.setTextColor(WeberinUi.WHITE); clock.setIncludeFontPadding(false); clock.setGravity(Gravity.CENTER_VERTICAL);
            clock.setTypeface(WeberinUi.font(this, 400));
            clock.setFormat12Hour("h:mm"); clock.setFormat24Hour("HH:mm"); put(scene, clock, 1700, 58, 176, 40);
            TextClock date = new TextClock(this); date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14);
            date.setTextColor(WeberinUi.MUTED); date.setIncludeFontPadding(false);
            date.setTypeface(WeberinUi.font(this, 400));
            date.setFormat12Hour("EEEE, d MMMM"); date.setFormat24Hour("EEEE, d MMMM");
            date.setTransformationMethod(new android.text.method.TransformationMethod() {
                @Override public CharSequence getTransformation(CharSequence source, View view) {
                    if (TextUtils.isEmpty(source)) return source;
                    return source.subSequence(0, 1).toString().toUpperCase(Locale.getDefault()) + source.subSequence(1, source.length());
                }
                @Override public void onFocusChanged(View view, CharSequence source, boolean focus, int direction, android.graphics.Rect previous) { }
            });
            if (preferences.getBoolean("weberin_show_date", true)) put(scene, date, 1700, 98, 210, 20);
            ClockFormat.apply(clock, config.clockFormat);
        }
    }
    private String networkLabel() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
            if (network == null || !network.isConnected()) return getString(R.string.weberin_network_offline);
            if (network.getType() != ConnectivityManager.TYPE_WIFI) return network.getTypeName();
            String label = "Wi-Fi";
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wifi == null ? null : wifi.getConnectionInfo(); String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !"<unknown ssid>".equalsIgnoreCase(ssid)) { ssid = ssid.replace("\"", "").trim(); if (!ssid.isEmpty()) label += " · " + ssid; }
            return label;
        } catch (SecurityException ignored) { return "Wi-Fi"; }
    }
    private void selectPage(int target) { if (page != target) { page = target; focusedPackage = null; populatePage(); } updateTabs(); }
    private void updateTabs() {
        for (int i = 0; i < tabs.length; i++) if (tabs[i] != null) {
            tabs[i].setTextColor(i == page ? 0xff202023 : 0xffdddddf);
            tabs[i].setTypeface(WeberinUi.font(this, i == page ? 600 : 500));
            tabs[i].setBackground(i == page ? WeberinUi.round(0xfff5f5f7, 0, 27) : null);
        }
    }
    private void populatePage() {
        pageContent.removeAllViews(); cards.clear(); weatherPanel = null; homeAction = null;
        if (page == 0) {
            if (preferences.getBoolean("weberin_home_full", false)) addFullHome();
            if (preferences.getBoolean("weberin_show_weather", preferences.getBoolean("weberin_home_full", false))) addWeatherPanel();
            if (preferences.getBoolean("weberin_show_favorites", config.dockRow)) addDock();
        } else if (page == 1) addAppGrid();
        else { TextView empty = text(getString(R.string.weberin_channels_later), 28, WeberinUi.MUTED, false);
            empty.setGravity(Gravity.CENTER); put(pageContent, empty, 460, 440, 1000, 100); }
        updateTabs();
    }
    private int surfaceOpacity() { return preferences.contains(Config.DOCK_OPACITY_KEY) ? Math.max(0, Math.min(255, config.dockOpacity * 255 / 100)) : 20; }
    private void addDock() {
        FrameLayout panel = new FrameLayout(this); panel.setClipChildren(false);
        panel.setBackground(new WeberinUi.Surface(preferences.getBoolean("weberin_surface_tint", false), surfaceOpacity()));
        put(pageContent, panel, 60, 716, 1784, 304);
        put(panel, text(getString(R.string.weberin_my_apps), 26, WeberinUi.WHITE, true), 32, 28, 700, 36);
        TextView hint = text(getString(R.string.weberin_hold_hint), 16, WeberinUi.MUTED, false); hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (preferences.getBoolean("weberin_show_hints", true)) put(panel, hint, 1050, 30, 702, 30);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false); scroll.setClipToPadding(false); put(panel, scroll, 24, 74, 1736, 214);
        FrameLayout row = new FrameLayout(this); row.setClipChildren(false); List<ResolveInfo> favorites = favorites();
        scroll.addView(row, new ScrollView.LayoutParams(Math.max(1736, (favorites.size() + 1) * 290 - 4), 214));
        for (int i = 0; i < favorites.size(); i++) put(row, tile(favorites.get(i), 270, 156), i * 290, 0, 286, 214);
        put(row, allAppsTile(), favorites.size() * 290, 0, 286, 214); wireCards(0);
    }
    private void addAppGrid() {
        put(pageContent, text(getString(R.string.weberin_apps), 26, WeberinUi.WHITE, true), 96, 197, 800, 38);
        FrameLayout panel = new FrameLayout(this); panel.setBackground(new WeberinUi.Surface(preferences.getBoolean("weberin_surface_tint", false), surfaceOpacity()));
        put(pageContent, panel, 60, 245, 1800, 750);
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(true); scroll.setPadding(0, 0, 0, 12);
        put(panel, scroll, 0, 10, 1800, 730); FrameLayout grid = new FrameLayout(this); grid.setClipChildren(false);
        int columns = WeberinGeometry.columns(preferences.contains("columns") ? config.columns : 5);
        int width = WeberinGeometry.gridCardWidth(columns), height = Math.round(width * 156f / 270);
        List<ResolveInfo> ordered = orderedApps();
        int stepY = columns <= 5 ? 245 : height + 89, rowCount = Math.max(1, (ordered.size() + columns - 1) / columns);
        scroll.addView(grid, new ScrollView.LayoutParams(1800, Math.max(718, rowCount * stepY)));
        for (int i = 0; i < ordered.size(); i++) put(grid, tile(ordered.get(i), width, height),
                WeberinGeometry.gridX(i % columns, columns) - 8, 7 + i / columns * stepY, width + 16, height + 58);
        if (ordered.isEmpty()) put(grid, text(getString(R.string.weberin_no_apps), 24, WeberinUi.MUTED, false), 36, 38, 1200, 50);
        wireCards(columns);
    }
    private FrameLayout tile(ResolveInfo app, int width, int height) {
        String name = launch.label(app), pkg = app.activityInfo.packageName;
        String captionName = pkg.startsWith("ru.okko") && name.equals("okko") ? "Okko" : name;
        FrameLayout holder = new FrameLayout(this); holder.setClipChildren(false);
        FrameLayout button = new WeberinUi.CardButton(this); button.setClipChildren(false); button.setFocusableInTouchMode(true);
        button.setId(View.generateViewId()); button.setTag(pkg); button.setContentDescription(name); put(holder, button, 0, 0, width + 16, height + 16);
        FrameLayout face = new FrameLayout(this); face.setBackground(WeberinUi.round(0x660e0e0e, 0x22aaaaaa, 20));
        face.setClipToOutline(true); put(button, face, 8, 8, width, height); Drawable banner = null;
        if (preferences.getBoolean("weberin_native_banners", false) || config.appBanners.containsKey(pkg)) {
            banner = banners.get(pkg); if (banner == null) { banner = launch.banner(app); if (banner != null) banners.put(pkg, banner); }
        }
        if (banner != null) { ImageView art = new ImageView(this); art.setImageDrawable(banner); art.setScaleType(ImageView.ScaleType.FIT_CENTER); put(face, art, 0, 0, width, height); }
        else put(face, new WeberinArtwork(this, pkg, name), 0, 0, width, height);
        TextView caption = text(captionName, 18, WeberinUi.MUTED, false); caption.setGravity(Gravity.CENTER); caption.setSingleLine(); caption.setEllipsize(TextUtils.TruncateAt.END);
        put(holder, caption, 4, height + 22, width + 8, 26);
        if (!preferences.getBoolean("weberin_show_app_names", true)) caption.setVisibility(View.INVISIBLE);
        button.setOnFocusChangeListener((v, focus) -> { focusCard(button, caption, focus); if (focus) { focusedPackage = pkg; focusedTab = -1; } });
        button.setOnClickListener(v -> launch.open(app)); button.setOnLongClickListener(v -> { showTileMenu(app); return true; }); cards.add(button); return holder;
    }
    private View allAppsTile() {
        FrameLayout holder = new FrameLayout(this), button = new WeberinUi.CardButton(this);
        button.setFocusableInTouchMode(true); button.setId(View.generateViewId()); button.setTag("@all"); button.setContentDescription(getString(R.string.weberin_all_apps));
        put(holder, button, 0, 0, 286, 172); FrameLayout face = new FrameLayout(this);
        face.setBackground(WeberinUi.round(0x990d0d0d, 0x22aaaaaa, 20)); put(button, face, 8, 8, 270, 156);
        put(face, new WeberinUi.AppsIcon(this), 108, 61, 54, 34);
        TextView caption = text(getString(R.string.weberin_all_short), 18, WeberinUi.MUTED, false); caption.setGravity(Gravity.CENTER); put(holder, caption, 0, 178, 286, 26);
        if (!preferences.getBoolean("weberin_show_app_names", true)) caption.setVisibility(View.INVISIBLE);
        button.setOnFocusChangeListener((v, focus) -> { focusCard(button, caption, focus); if (focus) { focusedPackage = "@all"; focusedTab = -1; } });
        button.setOnClickListener(v -> { focusedTab = -1; selectPage(1); if (!cards.isEmpty()) cards.get(0).requestFocus(); });
        button.setOnLongClickListener(v -> { showSettings(); return true; }); cards.add(button); return holder;
    }
    private void focusCard(View button, TextView caption, boolean focus) {
        android.graphics.drawable.GradientDrawable outline = WeberinUi.round(Color.TRANSPARENT, 0, 25); if (focus) outline.setStroke(2, 0xffdce6eb);
        button.setBackground(focus ? outline : null); caption.setTextColor(focus ? WeberinUi.WHITE : WeberinUi.MUTED);
        caption.setTypeface(WeberinUi.font(this, focus ? 600 : 400));
    }
    private void wireCards(int columns) {
        for (int i = 0; i < cards.size(); i++) {
            final int index = i; cards.get(i).setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == KeyEvent.KEYCODE_DPAD_UP ? -2 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 2 : 0;
                if (direction == 0) return false;
                if (columns == 0) { if (direction == -2) { if (homeAction != null && index < 2) homeAction.requestFocus(); else tabs[page].requestFocus(); }
                    else if (Math.abs(direction) == 1) cards.get(Math.max(0, Math.min(cards.size() - 1, index + direction))).requestFocus(); }
                else { int next = WeberinGeometry.neighbour(index, direction, columns, cards.size()); if (next < 0) tabs[page].requestFocus(); else cards.get(next).requestFocus(); }
                return true;
            });
        }
    }
    private boolean restoreCardFocus() { if (focusedPackage == null) return false; for (View card : cards) if (focusedPackage.equals(card.getTag())) return card.requestFocus(); return false; }

    private List<ResolveInfo> favorites() {
        List<ResolveInfo> result = new ArrayList<>(); Set<String> saved = preferences.getStringSet(FAVORITES, null);
        if (saved != null) { for (ResolveInfo app : apps) if (saved.contains(app.activityInfo.packageName)) result.add(app); }
        else {
            Set<String> removed = LibraryApps.loadRemoved(this);
            Order originalOrder = Order.load(Order.file(this));
            List<String> defaults = new ArrayList<>();
            int limit = Math.max(5, config.dockApps.size());
            if (originalOrder != null && originalOrder.dock.equals(Order.dockKey(config.dockApps))) {
                int docked = originalOrder.docked >= 0 ? originalOrder.docked : Math.min(config.columns, originalOrder.keys.size());
                limit = Math.max(limit, docked);
                for (int i = 0; i < Math.min(docked, originalOrder.keys.size()); i++) {
                    android.content.ComponentName component = android.content.ComponentName.unflattenFromString(originalOrder.keys.get(i));
                    if (component != null) defaults.add(component.getPackageName());
                }
            }
            defaults.addAll(config.dockApps); defaults.addAll(new java.util.TreeSet<>(LibraryApps.load(this)));
            Collections.addAll(defaults, "com.google.android.youtube.tv", "com.happproxy", "com.happproxy.tv", "ru.kinopoisk.tv", "ru.ivi.client", "ru.okko");
            for (String pkg : defaults) for (ResolveInfo app : apps) { if (result.size() >= limit) break;
                if (app.activityInfo.packageName.equals(pkg) && !removed.contains(pkg) && !config.hiddenApps.contains(pkg) && !result.contains(app)) result.add(app); }
            for (ResolveInfo app : apps) { if (result.size() >= limit) break;
                if ((app.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !config.hiddenApps.contains(app.activityInfo.packageName)
                        && !removed.contains(app.activityInfo.packageName) && !result.contains(app)) result.add(app); }
        }
        List<String> order = new ArrayList<>();
        try { org.json.JSONArray json = new org.json.JSONArray(preferences.getString(FAVORITE_ORDER, "[]")); for (int i = 0; i < json.length(); i++) order.add(json.getString(i)); } catch (org.json.JSONException ignored) { }
        if (!order.isEmpty()) Collections.sort(result, (a, b) -> Integer.compare(rank(order, a), rank(order, b)));
        else if (saved == null) {
            Map<String, Integer> ranks = Order.ranks(Order.load(Order.file(this)), config.dockApps);
            Collections.sort(result, (a, b) -> Order.compare(ranks, new android.content.ComponentName(a.activityInfo.packageName, a.activityInfo.name).flattenToShortString(),
                    new android.content.ComponentName(b.activityInfo.packageName, b.activityInfo.name).flattenToShortString()));
        }
        return result;
    }
    private int rank(List<String> order, ResolveInfo app) { int index = order.indexOf(app.activityInfo.packageName); return index < 0 ? Integer.MAX_VALUE : index; }
    private List<ResolveInfo> orderedApps() {
        List<ResolveInfo> ordered = new ArrayList<>(favorites()), remaining = new ArrayList<>();
        for (ResolveInfo app : apps) if (!ordered.contains(app)) remaining.add(app);
        Map<String, Integer> original = Order.ranks(Order.load(Order.file(this)), config.dockApps);
        Collections.sort(remaining, (a, b) -> {
            int saved = Order.compare(original,
                    new android.content.ComponentName(a.activityInfo.packageName, a.activityInfo.name).flattenToShortString(),
                    new android.content.ComponentName(b.activityInfo.packageName, b.activityInfo.name).flattenToShortString());
            return saved != 0 ? saved : Integer.compare(artworkOrder(a), artworkOrder(b));
        });
        ordered.addAll(remaining);
        List<String> custom = new ArrayList<>();
        try { org.json.JSONArray json = new org.json.JSONArray(preferences.getString("weberin_grid_order", "[]"));
            for (int i = 0; i < json.length(); i++) custom.add(json.getString(i));
        } catch (org.json.JSONException ignored) { }
        if (!custom.isEmpty()) Collections.sort(ordered, (a, b) -> Integer.compare(rank(custom, a), rank(custom, b)));
        return ordered;
    }
    private int artworkOrder(ResolveInfo app) {
        String name = launch.label(app).toLowerCase(Locale.ROOT).replace(" ", "");
        String[] names = {"vkвидео", "wink", "start", "premier", "kion", "триколор", "spotify", "браузер", "файлы", "настройки"};
        for (int i = 0; i < names.length; i++) if (name.equals(names[i])) return i;
        return names.length;
    }
    private void moveGridApp(List<ResolveInfo> ordered, int from, int to) {
        Collections.swap(ordered, from, to); org.json.JSONArray json = new org.json.JSONArray();
        for (ResolveInfo app : ordered) json.put(app.activityInfo.packageName);
        preferences.edit().putString("weberin_grid_order", json.toString()).apply(); populatePage(); focusNearest(to);
    }
    private void showTileMenu(ResolveInfo app) {
        String pkg = app.activityInfo.packageName; List<ResolveInfo> current = favorites(); int found = -1;
        for (int i = 0; i < current.size(); i++) if (pkg.equals(current.get(i).activityInfo.packageName)) found = i;
        final int index = found; List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add(getString(index >= 0 ? R.string.library_remove_shortcut : R.string.library_add_home));
        actions.add(() -> { Set<String> next = new HashSet<>(); for (ResolveInfo favorite : current) next.add(favorite.activityInfo.packageName);
            if (index >= 0) { next.remove(pkg); current.remove(index); } else { next.add(pkg); current.add(app); }
            saveFavorites(current, next); populatePage(); focusNearest(index); });
        if (page == 1) {
            List<ResolveInfo> grid = orderedApps(); int position = grid.indexOf(app);
            if (position > 0) { labels.add(getString(R.string.weberin_move_left)); actions.add(() -> moveGridApp(grid, position, position - 1)); }
            if (position >= 0 && position + 1 < grid.size()) { labels.add(getString(R.string.weberin_move_right)); actions.add(() -> moveGridApp(grid, position, position + 1)); }
        } else {
            if (index > 0) { labels.add(getString(R.string.weberin_move_left)); actions.add(() -> moveFavorite(current, index, index - 1)); }
            if (index >= 0 && index + 1 < current.size()) { labels.add(getString(R.string.weberin_move_right)); actions.add(() -> moveFavorite(current, index, index + 1)); }
        }
        labels.add(getString(R.string.library_app_info)); actions.add(() -> openSettingsTarget(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg))));
        new AlertDialog.Builder(this).setTitle(launch.label(app)).setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void saveFavorites(List<ResolveInfo> order, Set<String> selected) {
        org.json.JSONArray json = new org.json.JSONArray(); for (ResolveInfo app : order) json.put(app.activityInfo.packageName);
        preferences.edit().putStringSet(FAVORITES, selected).putString(FAVORITE_ORDER, json.toString()).apply();
    }
    private void moveFavorite(List<ResolveInfo> current, int from, int to) {
        Collections.swap(current, from, to); Set<String> selected = new HashSet<>(); for (ResolveInfo app : current) selected.add(app.activityInfo.packageName);
        saveFavorites(current, selected); populatePage(); focusNearest(to);
    }
    private void focusNearest(int previous) { pageContent.post(() -> { if (!restoreCardFocus() && !cards.isEmpty()) cards.get(Math.max(0, Math.min(cards.size() - 1, previous))).requestFocus(); }); }

    private void addFullHome() {
        if (preferences.getBoolean("weberin_show_news", true)) {
        TextView eyebrow = text(getString(R.string.weberin_home_eyebrow), 16, 0xffa9b0bb, true); eyebrow.setLetterSpacing(.10f); put(pageContent, eyebrow, 96, 222, 950, 28);
        TextView title = text(getString(R.string.weberin_new_releases), 76, WeberinUi.WHITE, true); title.setTypeface(WeberinUi.font(this, 700)); put(pageContent, title, 96, 263, 1000, 92);
        // The artwork's films are examples. The owner has not chosen a content source yet.
        put(pageContent, text(getString(R.string.weberin_news_source_missing), 24, WeberinUi.MUTED, false), 96, 382, 1000, 64);
        }
        if (preferences.getBoolean("weberin_show_xbox", true)) for (ResolveInfo app : apps) {
            if (!app.activityInfo.packageName.startsWith("com.microsoft.xbox")) continue;
            FrameLayout xbox = new FrameLayout(this); xbox.setFocusable(true); xbox.setBackground(WeberinUi.round(0xfff5f5f7, 0, 18));
            xbox.setContentDescription("Xbox"); put(xbox, new WeberinUi.XboxIcon(this), 25, 17, 32, 32);
            put(xbox, text("XBOX", 24, 0xff202023, true), 73, 0, 86, 66);
            xbox.setOnClickListener(v -> launch.open(app)); xbox.setOnFocusChangeListener((v, focus) -> xbox.setBackground(WeberinUi.round(0xfff5f5f7, focus ? 0xff92acc6 : 0, 18)));
            xbox.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { tabs[0].requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!cards.isEmpty()) cards.get(0).requestFocus(); else tabs[0].requestFocus(); return true; }
                return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            homeAction = xbox; put(pageContent, xbox, 96, 616, 165, 66); break;
        }
    }
    private void addWeatherPanel() { weatherPanel = new FrameLayout(this); put(pageContent, weatherPanel, 1512, 290, 312, 330); updateWeatherPanel(); requestWeatherIfNeeded(); }
    private void weatherLine(CharSequence value, int size, int color, int y, int height) { TextView line = text(value, size, color, false); line.setGravity(Gravity.CENTER); put(weatherPanel, line, 0, y, 312, height); }
    private void updateWeatherPanel() {
        if (weatherPanel == null) return; weatherPanel.removeAllViews();
        String requested = preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city));
        WeberinWeather.Reading reading = requested.equals(weatherCityLoaded) ? weatherReading : null;
        weatherLine(reading == null ? requested : reading.city, 22, WeberinUi.WHITE, 0, 34);
        if (reading == null) { weatherLine(getString(weatherFailed && requested.equals(weatherCityLoaded) ? R.string.weberin_weather_unavailable : R.string.weberin_weather_loading), 16, WeberinUi.MUTED, 44, 30); return; }
        weatherLine(getString(R.string.weberin_weather_humidity, reading.humidity), 16, 0xffacbece, 34, 28);
        int condition = WeberinWeather.condition(reading.code); put(weatherPanel, new WeberinUi.WeatherIcon(this, condition), 124, 78, 64, 66);
        TextView temperature = text(String.format(Locale.getDefault(), "%s%d°", reading.temperature < 0 ? "−" : "", Math.abs(reading.temperature)), 70, Color.WHITE, false);
        temperature.setTypeface(WeberinUi.font(this, 300)); temperature.setGravity(Gravity.CENTER); put(weatherPanel, temperature, 0, 133, 312, 80);
        int[] descriptions = {R.string.weberin_weather_clear, R.string.weberin_weather_cloudy, R.string.weberin_weather_fog, R.string.weberin_weather_rain, R.string.weberin_weather_snow, R.string.weberin_weather_storm};
        weatherLine(getString(descriptions[condition]), 22, 0xffe0e7ef, 220, 32);
        weatherLine(getString(R.string.weberin_weather_range, reading.low, reading.high), 16, 0xffacbece, 268, 30); weatherLine("Open-Meteo.com", 11, 0xff86909c, 305, 20);
    }
    private void requestWeatherIfNeeded() {
        String city = preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city)); long interval = weatherFailed ? 5 * 60_000L : 30 * 60_000L;
        if (city.equals(weatherCityPending) || city.equals(weatherCityLoaded) && SystemClock.elapsedRealtime() - weatherCheckedAt < interval) return;
        weatherCityPending = city; int generation = ++weatherGeneration; String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        weatherExecutor.execute(() -> {
            WeberinWeather.Reading result = null; try { result = WeberinWeather.fetch(city, language); } catch (IOException | org.json.JSONException | RuntimeException ignored) { }
            final WeberinWeather.Reading reading = result; runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != weatherGeneration) return;
                weatherCityPending = ""; weatherCityLoaded = city; weatherReading = reading; weatherFailed = reading == null; weatherCheckedAt = SystemClock.elapsedRealtime(); updateWeatherPanel();
            });
        });
    }
    private void showSettings() {
        if (overlay != null) return;
        // Keep the two panels modal even if firmware chooses its own geometric focus neighbour.
        for (int i = 0; i < scene.getChildCount(); i++) {
            View child = scene.getChildAt(i);
            child.setFocusable(false);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (child instanceof android.view.ViewGroup) ((android.view.ViewGroup) child)
                    .setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        List<ResolveInfo> ordered = orderedApps();
        settingsPanel = new WeberinSettings(this, ordered); overlay = settingsPanel.create(); put(scene, overlay, 0, 0, 1920, 1080);
    }
    @Override public void onBackPressed() {
        if (settingsPanel != null) { settingsPanel.handleBack(); return; }
        if (page != 0) { focusedTab = -1; selectPage(0); if (!cards.isEmpty()) cards.get(0).requestFocus(); else tabs[0].requestFocus(); }
        else moveTaskToBack(true);
    }
    @Override public Activity activity() { return this; }
    @Override public SharedPreferences preferences() { return preferences; }
    @Override public void onSettingsChanged() { config = Config.load(this); launch = new WeberinLaunch(this, config); banners.clear(); }
    @Override public void onCloseSettings() {
        if (overlay != null) scene.removeView(overlay); overlay = null; settingsPanel = null;
        config = Config.load(this); launch = new WeberinLaunch(this, config); render(); scene.post(() -> settingsButton.requestFocus());
    }
    @Override public void onOriginalInterface() { preferences.edit().putBoolean("weberin_layout", false).apply(); finish(); }
    @Override public void onWallpaperPicker() { showWallpaperPicker(); }
    @Override public void onLauncherSettings() { startActivityForResult(new Intent(this, LauncherSettingsActivity.class), 71); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 71 && resultCode == RESULT_OK && data != null
                && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            startActivity(new Intent(this, MainActivity.class).putExtra(LauncherSettingsActivity.EXTRA_REORDER, true));
            finish();
        }
    }
    @Override public void onWeatherCityPicker() { showWeatherCityPicker(); }
    @Override public void onReleaseLauncherMemory() { banners.clear(); wallpaper = null; wallpaperKey = null; }
    @Override public void openStartupIntent(Intent intent) { launch.openIntent(intent); }
    @Override public void openStartupInput(String inputId) { launch.openInput(inputId); }
    private void showWeatherCityPicker() {
        EditText input = new EditText(this); input.setSingleLine(); input.setText(preferences.getString("weberin_weather_city", getString(R.string.weberin_weather_default_city)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.weberin_weather_city).setView(input).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String city = input.getText().toString().trim(); if (city.length() < 2 || city.length() > 80) { input.setError(getString(R.string.weberin_weather_invalid_city)); return; }
            preferences.edit().putString("weberin_weather_city", city).apply(); weatherGeneration++; weatherCityPending = ""; weatherCityLoaded = ""; weatherReading = null; weatherCheckedAt = 0; weatherFailed = false;
            if (settingsPanel != null) settingsPanel.refresh(); else populatePage(); dialog.dismiss();
        })); dialog.show();
    }
    private void showWallpaperPicker() {
        try {
            List<String> pictures = new ArrayList<>(), names = new ArrayList<>(); pictures.add("theme"); names.add(getString(R.string.wallpaper_theme_default)); pictures.add("none"); names.add(getString(R.string.wallpaper_none));
            String[] files = getAssets().list("weberin"); if (files != null) for (String file : files) if (file.endsWith(".jpg") || file.endsWith(".png")) {
                pictures.add(file); names.add(file.replaceFirst("^\\d+-", "").replace(".png", "").replace(".jpg", "").replace('-', ' ')); }
            new AlertDialog.Builder(this).setTitle(R.string.weberin_wallpaper).setSingleChoiceItems(names.toArray(new String[0]), pictures.indexOf(preferences.getString("weberin_wallpaper", DEFAULT_WALLPAPER)), (dialog, which) -> {
                preferences.edit().putString("weberin_wallpaper", pictures.get(which)).apply(); wallpaperKey = null;
                if (settingsPanel != null) { onSettingsChanged(); settingsPanel.refresh(); } else render(); dialog.dismiss();
            }).setNegativeButton(android.R.string.cancel, null).show();
        } catch (IOException e) { Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show(); }
    }
    private void openSettingsTarget(Intent intent) { launch.openIntent(intent); }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level); if (level >= TRIM_MEMORY_UI_HIDDEN) banners.clear(); if (level >= TRIM_MEMORY_RUNNING_CRITICAL) { wallpaper = null; wallpaperKey = null; }
    }
    @Override protected void onDestroy() { weatherGeneration++; weatherExecutor.shutdownNow(); startup.close(); banners.clear(); wallpaper = null; super.onDestroy(); }
}
