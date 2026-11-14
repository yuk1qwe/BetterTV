package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
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
import java.io.IOException;
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
public final class WeberinActivity extends Activity implements WeberinSettings.Host, WeberinStartup.Host, WeberinFavoritesEditor.Host {
    private static final String FAVORITES = "weberin_favorites", FAVORITE_ORDER = "weberin_favorites_order";
    private static final String DEFAULT_WALLPAPER = "01-windows-black-1080p.png";
    private SharedPreferences preferences;
    private Config config;
    private WeberinLaunch launch;
    private WeberinStartup startup;
    private FrameLayout scene, pageContent, weatherPanel, overlay;
    private FrameLayout modalHost, favoritesOverlay;
    private View settingsFocusBeforeEditor;
    private boolean sceneNeedsRender;
    private WeberinSettings settingsPanel;
    private WeberinFavoritesEditor favoritesEditor;
    private WeberinCatalog.Snapshot snapshot;
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Drawable> icons = new android.util.LruCache<>(32);
    private final Set<String> missingBanners = new HashSet<>();
    private List<ResolveInfo> cachedFavorites, cachedOrdered;
    private boolean loading, resumed, packagesDirty = true, refreshAgain, sharedPrepared, contentReported;
    private int catalogGeneration;
    private FrameLayout pagesHost;
    private final PageState[] pages = new PageState[3];
    private static final class PageState {
        final FrameLayout view, weather;
        final List<View> cards;
        final View action, editor;
        PageState(FrameLayout view, List<View> cards, FrameLayout weather, View action, View editor) {
            this.view = view; this.cards = new ArrayList<>(cards); this.weather = weather; this.action = action; this.editor = editor;
        }
    }
    private final android.content.BroadcastReceiver packages = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            packagesDirty = true;
            if (resumed) refreshCatalog();
        }
    };
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final TextView[] tabs = new TextView[3];
    private ImageView settingsButton;
    private View homeAction;
    private View dockEditorButton;
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
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!preferences.getBoolean("weberin_layout", true)) { finish(); return; }
        WeberinDeviceControls.applyWindowBrightness(this, preferences);
        if (settingsPanel != null && favoritesEditor == null) settingsPanel.refresh();
        if (scene == null) {
            FrameLayout loadingScreen = new FrameLayout(this); loadingScreen.setBackgroundColor(Color.BLACK);
            TextView title = text("Weberin TV", 34, WeberinUi.WHITE, true); title.setGravity(Gravity.CENTER);
            loadingScreen.addView(title, new FrameLayout.LayoutParams(-1, -1));
            setContentView(loadingScreen);
        }
        refreshCatalog();
    }
    @Override protected void onPause() { resumed = false; startup.pause(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page); out.putString("focused_package", focusedPackage); super.onSaveInstanceState(out);
    }
    private TextView text(CharSequence value, int size, int color, boolean bold) { return WeberinUi.text(this, value, size, color, bold); }
    private void put(FrameLayout parent, View view, int x, int y, int w, int h) { WeberinUi.put(parent, view, x, y, w, h); }

    private void refreshCatalog() {
        if (loading) { refreshAgain = true; return; }
        loading = true;
        final boolean reloadPackages = packagesDirty; packagesDirty = false;
        final WeberinCatalog.Snapshot previous = snapshot;
        catalogExecutor.execute(() -> {
            WeberinCatalog.Snapshot loaded = null;
            android.os.Trace.beginSection("Weberin.catalog");
            try { loaded = WeberinCatalog.load(this, previous, reloadPackages); }
            catch (RuntimeException | OutOfMemoryError failure) {
                android.util.Log.w("WeberinCatalog", "Could not refresh launcher", failure);
            } finally { android.os.Trace.endSection(); }
            final WeberinCatalog.Snapshot result = loaded;
            runOnUiThread(() -> {
                loading = false;
                if (isDestroyed() || isFinishing()) return;
                if (result != null && result != snapshot) {
                    if (previous != null && !previous.config.locale.equals(result.config.locale)) { recreate(); return; }
                    snapshot = result; config = result.config; launch = result.launch;
                    apps.clear(); apps.addAll(result.apps); cachedFavorites = null; cachedOrdered = null;
                    wallpaper = result.wallpaper; wallpaperKey = result.wallpaperKey;
                    catalogGeneration++; banners.clear(); missingBanners.clear();
                    if (reloadPackages) icons.evictAll();
                    invalidatePages();
                    if (!sharedPrepared || previous == null || previous.config.handleViewInputs != config.handleViewInputs
                            || previous.config.restoreKeyServices != config.restoreKeyServices) {
                        MainActivity.prepareSharedFeatures(this, config); sharedPrepared = true;
                    }
                    sceneNeedsRender = true;
                    if (resumed && settingsPanel == null && favoritesEditor == null) render();
                    else if (resumed && settingsPanel != null && favoritesEditor == null) settingsPanel.refresh();
                }
                if (result == null) packagesDirty |= reloadPackages;
                if (result == null && scene == null && resumed) {
                    TextView retry = text(getString(R.string.not_available), 28, WeberinUi.WHITE, false);
                    retry.setGravity(Gravity.CENTER); retry.setFocusableInTouchMode(true);
                    retry.setOnClickListener(v -> refreshCatalog()); setContentView(retry); retry.requestFocus();
                }
                if (resumed && config != null) {
                    if (sceneNeedsRender && settingsPanel == null && favoritesEditor == null) render();
                    startup.resume(config);
                    if (weatherPanel != null) { updateWeatherPanel(); requestWeatherIfNeeded(); }
                }
                if (refreshAgain) { refreshAgain = false; refreshCatalog(); }
            });
        });
    }

    private void render() {
        int tabToRestore = focusedTab;
        if (snapshot != null) { wallpaper = snapshot.wallpaper; wallpaperKey = snapshot.wallpaperKey; }
        sceneNeedsRender = false;
        settingsPanel = null; overlay = null; scene = new FrameLayout(this);
        modalHost = new FrameLayout(this); put(modalHost, scene, 0, 0, 1920, 1080);
        scene.setClipChildren(false); scene.setBackgroundColor(Color.BLACK);
        scene.setFocusableInTouchMode(true); scene.requestFocus(); addWallpaper();
        pagesHost = new FrameLayout(this); pagesHost.setClipChildren(false);
        put(scene, pagesHost, 0, 0, 1920, 1080); addHeader();
        setContentView(new WeberinUi.Viewport(this, modalHost)); populatePage();
        if (!contentReported) {
            final FrameLayout drawnScene = scene;
            drawnScene.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
                private boolean posted;
                @Override public void onDraw() {
                    if (posted || contentReported || !resumed) return;
                    posted = true;
                    // Removing an OnDrawListener during drawing is forbidden; report after this real
                    // content frame, rather than treating the earlier loading screen as ready.
                    drawnScene.post(() -> {
                        boolean valid = !isDestroyed() && !isFinishing() && scene == drawnScene;
                        if (valid && !resumed) { posted = false; return; }
                        android.view.ViewTreeObserver observer = drawnScene.getViewTreeObserver();
                        if (observer.isAlive()) observer.removeOnDrawListener(this);
                        if (valid && !contentReported) { contentReported = true; reportFullyDrawn(); }
                    });
                }
            });
        }
        scene.post(() -> {
            if (tabToRestore >= 0) tabs[tabToRestore].requestFocus();
            else if (!restoreCardFocus()) { if (!cards.isEmpty()) cards.get(0).requestFocus(); else if (homeAction != null) homeAction.requestFocus(); else tabs[page].requestFocus(); }
        });
    }

    private void addWallpaper() {
        if (wallpaper != null) {
            ImageView image = new ImageView(this); image.setImageBitmap(wallpaper); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            put(scene, image, 0, 0, 1920, 1080);
        }
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
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!restoreCardFocus()) { if (!cards.isEmpty()) cards.get(0).requestFocus(); else if (homeAction != null) homeAction.requestFocus(); } return true; }
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
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!restoreCardFocus()) { if (!cards.isEmpty()) cards.get(cards.size() - 1).requestFocus(); else if (homeAction != null) homeAction.requestFocus(); } return true; }
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
    private void selectPage(int target) {
        if (page != target) { page = target; focusedPackage = null; showPage(); }
        updateTabs();
    }
    private void updateTabs() {
        for (int i = 0; i < tabs.length; i++) if (tabs[i] != null) {
            tabs[i].setTextColor(i == page ? 0xff202023 : 0xffdddddf);
            tabs[i].setTypeface(WeberinUi.font(this, i == page ? 600 : 500));
            tabs[i].setBackground(i == page ? WeberinUi.round(0xfff5f5f7, 0, 27) : null);
        }
    }
    private void populatePage() {
        invalidatePages(); pagesHost.removeAllViews(); showPage();
    }
    private void invalidatePages() { java.util.Arrays.fill(pages, null); }
    private void showPage() {
        for (int i = 0; i < pagesHost.getChildCount(); i++) pagesHost.getChildAt(i).setVisibility(View.GONE);
        PageState saved = pages[page];
        if (saved != null) {
            pageContent = saved.view; cards.clear(); cards.addAll(saved.cards); weatherPanel = saved.weather; homeAction = saved.action; dockEditorButton = saved.editor;
            pageContent.setVisibility(View.VISIBLE);
            if (weatherPanel != null) { updateWeatherPanel(); requestWeatherIfNeeded(); }
            return;
        }
        pageContent = new FrameLayout(this); pageContent.setClipChildren(false);
        put(pagesHost, pageContent, 0, 0, 1920, 1080);
        cards.clear(); weatherPanel = null; homeAction = null; dockEditorButton = null;
        if (page == 0) {
            if (preferences.getBoolean("weberin_home_full", false)) addFullHome();
            if (preferences.getBoolean("weberin_show_weather", preferences.getBoolean("weberin_home_full", false))) addWeatherPanel();
            if (preferences.getBoolean("weberin_show_favorites", config.dockRow)) addDock();
        } else if (page == 1) addAppGrid();
        else { TextView empty = text(getString(R.string.weberin_channels_later), 28, WeberinUi.MUTED, false);
            empty.setGravity(Gravity.CENTER); put(pageContent, empty, 460, 440, 1000, 100); }
        pages[page] = new PageState(pageContent, cards, weatherPanel, homeAction, dockEditorButton);
        updateTabs();
    }
    private int surfaceOpacity() { return preferences.contains(Config.DOCK_OPACITY_KEY) ? Math.max(0, Math.min(255, config.dockOpacity * 255 / 100)) : 20; }
    private int cardScale() { return Math.max(90, Math.min(110, preferences.getInt("weberin_card_scale", 100))); }
    private void addDock() {
        int width = 270 * cardScale() / 100, height = Math.round(width * 156f / 270), extra = height - 156, step = width + 20;
        FrameLayout panel = new FrameLayout(this); panel.setClipChildren(false);
        panel.setBackground(new WeberinUi.Surface(preferences.getBoolean("weberin_surface_tint", false), surfaceOpacity()));
        put(pageContent, panel, 60, 716 - extra, 1784, 304 + extra);
        TextView edit = text(getString(R.string.weberin_my_apps), 26, WeberinUi.WHITE, true);
        edit.setFocusableInTouchMode(true); edit.setOnClickListener(v -> onFavoritesEditor());
        edit.setOnFocusChangeListener((v, focus) -> edit.setBackground(focus ? WeberinUi.round(0x331a1a1a, 0xffdce6eb, 8) : null));
        edit.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) { if (homeAction != null) homeAction.requestFocus(); else tabs[0].requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (!cards.isEmpty()) cards.get(0).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { settingsButton.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_LEFT;
        });
        dockEditorButton = edit; put(panel, edit, 32, 28, 700, 36);
        TextView hint = text(getString(R.string.weberin_hold_hint), 16, WeberinUi.MUTED, false); hint.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (preferences.getBoolean("weberin_show_hints", true)) put(panel, hint, 1050, 30, 702, 30);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false); scroll.setClipToPadding(false); put(panel, scroll, 24, 74, 1736, 214 + extra);
        FrameLayout row = new FrameLayout(this); row.setClipChildren(false); List<ResolveInfo> favorites = favorites();
        scroll.addView(row, new ScrollView.LayoutParams(Math.max(1736, (favorites.size() + 1) * step - 4), 214 + extra));
        for (int i = 0; i < favorites.size(); i++) put(row, tile(favorites.get(i), width, height), i * step, 0, width + 16, height + 58);
        put(row, allAppsTile(width, height), favorites.size() * step, 0, width + 16, height + 58); wireCards(0);
    }
    private void addAppGrid() {
        put(pageContent, text(getString(R.string.weberin_apps), 26, WeberinUi.WHITE, true), 96, 197, 800, 38);
        FrameLayout panel = new FrameLayout(this); panel.setBackground(new WeberinUi.Surface(preferences.getBoolean("weberin_surface_tint", false), surfaceOpacity()));
        put(pageContent, panel, 60, 245, 1800, 750);
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(true); scroll.setPadding(0, 0, 0, 12);
        put(panel, scroll, 0, 10, 1800, 730); FrameLayout grid = new FrameLayout(this); grid.setClipChildren(false);
        int columns = WeberinGeometry.columns(preferences.contains("columns") ? config.columns : 5);
        int width = Math.min(WeberinGeometry.gridCardWidth(columns) * cardScale() / 100, (1728 - (columns - 1) * 16) / columns);
        int height = Math.round(width * 156f / 270);
        List<ResolveInfo> ordered = orderedApps();
        int stepY = columns <= 5 ? Math.max(245, height + 89) : height + 89, rowCount = Math.max(1, (ordered.size() + columns - 1) / columns);
        scroll.addView(grid, new ScrollView.LayoutParams(1800, Math.max(718, rowCount * stepY)));
        for (int i = 0; i < ordered.size(); i++) put(grid, tile(ordered.get(i), width, height),
                WeberinGeometry.gridX(i % columns, columns, width) - 8, 7 + i / columns * stepY, width + 16, height + 58);
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
        face.setClipToOutline(true); put(button, face, 8, 8, width, height);
        View fallback = new WeberinArtwork(this, pkg, name); put(face, fallback, 0, 0, width, height);
        if (preferences.getBoolean("weberin_native_banners", false) || config.appBanners.containsKey(pkg)) {
            ImageView art = new ImageView(this); art.setScaleType(ImageView.ScaleType.FIT_CENTER); put(face, art, 0, 0, width, height);
            bindBanner(app, art, fallback);
        }
        WeberinUi.Caption caption = WeberinUi.newCaption(this, captionName, 18);
        put(holder, caption, 4, height + 22, width + 8, 26);
        if (!preferences.getBoolean("weberin_show_app_names", true)) caption.setVisibility(View.INVISIBLE);
        button.setOnFocusChangeListener((v, focus) -> { focusCard(button, caption, focus); if (focus) { focusedPackage = pkg; focusedTab = -1; } });
        button.setOnClickListener(v -> launch.open(app)); button.setOnLongClickListener(v -> { showTileMenu(app); return true; }); cards.add(button); return holder;
    }
    private View allAppsTile(int width, int height) {
        FrameLayout holder = new FrameLayout(this), button = new WeberinUi.CardButton(this);
        button.setFocusableInTouchMode(true); button.setId(View.generateViewId()); button.setTag("@all"); button.setContentDescription(getString(R.string.weberin_all_apps));
        put(holder, button, 0, 0, width + 16, height + 16); FrameLayout face = new FrameLayout(this);
        face.setBackground(WeberinUi.round(0x990d0d0d, 0x22aaaaaa, 20)); put(button, face, 8, 8, width, height);
        put(face, new WeberinUi.AppsIcon(this), (width - 54) / 2, (height - 34) / 2, 54, 34);
        WeberinUi.Caption caption = WeberinUi.newCaption(this, getString(R.string.weberin_all_short), 18); put(holder, caption, 0, height + 22, width + 16, 26);
        if (!preferences.getBoolean("weberin_show_app_names", true)) caption.setVisibility(View.INVISIBLE);
        button.setOnFocusChangeListener((v, focus) -> { focusCard(button, caption, focus); if (focus) { focusedPackage = "@all"; focusedTab = -1; } });
        button.setOnClickListener(v -> { focusedTab = -1; selectPage(1); if (!cards.isEmpty()) cards.get(0).requestFocus(); });
        button.setOnLongClickListener(v -> { onFavoritesEditor(); return true; }); cards.add(button); return holder;
    }
    private void focusCard(View button, WeberinUi.Caption caption, boolean focus) {
        android.graphics.drawable.GradientDrawable outline = WeberinUi.round(Color.TRANSPARENT, 0, 25); if (focus) outline.setStroke(2, 0xffdce6eb);
        button.setBackground(focus ? outline : null); caption.focus(focus);
    }
    private void wireCards(int columns) {
        for (int i = 0; i < cards.size(); i++) {
            final int index = i; cards.get(i).setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == KeyEvent.KEYCODE_DPAD_UP ? -2 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 2 : 0;
                if (direction == 0) return false;
                if (columns == 0) { if (direction == -2) { if (index == 0 && dockEditorButton != null) dockEditorButton.requestFocus(); else if (index >= cards.size() - 2) settingsButton.requestFocus(); else tabs[page].requestFocus(); }
                    else if (Math.abs(direction) == 1) cards.get(Math.max(0, Math.min(cards.size() - 1, index + direction))).requestFocus(); }
                else { int next = WeberinGeometry.neighbour(index, direction, columns, cards.size()); if (next < 0) tabs[page].requestFocus(); else cards.get(next).requestFocus(); }
                return true;
            });
        }
    }
    private boolean restoreCardFocus() { if (focusedPackage == null) return false; for (View card : cards) if (focusedPackage.equals(card.getTag())) return card.requestFocus(); return false; }

    private List<ResolveInfo> favorites() {
        if (cachedFavorites != null) return new ArrayList<>(cachedFavorites);
        List<ResolveInfo> result = new ArrayList<>(); Set<String> saved = preferences.getStringSet(FAVORITES, null);
        if (saved != null) { for (ResolveInfo app : apps) if (saved.contains(app.activityInfo.packageName)) result.add(app); }
        else {
            Set<String> removed = snapshot.libraryRemoved;
            Order originalOrder = snapshot.originalOrder;
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
            defaults.addAll(config.dockApps); defaults.addAll(new java.util.TreeSet<>(snapshot.libraryAdded));
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
            Map<String, Integer> ranks = Order.ranks(snapshot.originalOrder, config.dockApps);
            Collections.sort(result, (a, b) -> Order.compare(ranks, new android.content.ComponentName(a.activityInfo.packageName, a.activityInfo.name).flattenToShortString(),
                    new android.content.ComponentName(b.activityInfo.packageName, b.activityInfo.name).flattenToShortString()));
        }
        cachedFavorites = new ArrayList<>(result); return result;
    }
    private int rank(List<String> order, ResolveInfo app) { int index = order.indexOf(app.activityInfo.packageName); return index < 0 ? Integer.MAX_VALUE : index; }
    private List<ResolveInfo> orderedApps() {
        if (cachedOrdered != null) return new ArrayList<>(cachedOrdered);
        List<ResolveInfo> ordered = new ArrayList<>(favorites()), remaining = new ArrayList<>();
        for (ResolveInfo app : apps) if (!ordered.contains(app)) remaining.add(app);
        Map<String, Integer> original = Order.ranks(snapshot.originalOrder, config.dockApps);
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
        cachedOrdered = new ArrayList<>(ordered); return ordered;
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
        preferences.edit().putString("weberin_grid_order", json.toString()).apply(); cachedOrdered = null; populatePage(); focusNearest(to);
    }
    private void showTileMenu(ResolveInfo app) {
        String pkg = app.activityInfo.packageName; List<ResolveInfo> current = favorites(); int found = -1;
        for (int i = 0; i < current.size(); i++) if (pkg.equals(current.get(i).activityInfo.packageName)) found = i;
        final int index = found; List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.wf_title)); actions.add(this::onFavoritesEditor);
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
        cachedFavorites = null; cachedOrdered = null;
    }
    private void moveFavorite(List<ResolveInfo> current, int from, int to) {
        Collections.swap(current, from, to); Set<String> selected = new HashSet<>(); for (ResolveInfo app : current) selected.add(app.activityInfo.packageName);
        saveFavorites(current, selected); populatePage(); focusNearest(to);
    }
    private void focusNearest(int previous) { pageContent.post(() -> { if (!restoreCardFocus() && !cards.isEmpty()) cards.get(Math.max(0, Math.min(cards.size() - 1, previous))).requestFocus(); }); }

    private void addFullHome() {
        int x = leftWeather() ? 560 : 96;
        if (preferences.getBoolean("weberin_show_news", true)) {
        TextView eyebrow = text(getString(R.string.weberin_home_eyebrow), 16, 0xffa9b0bb, true); eyebrow.setLetterSpacing(.10f); put(pageContent, eyebrow, x, 222, 950, 28);
        TextView title = text(getString(R.string.weberin_new_releases), 76, WeberinUi.WHITE, true); title.setTypeface(WeberinUi.font(this, 700)); put(pageContent, title, x, 263, 1000, 92);
        // The artwork's films are examples. The owner has not chosen a content source yet.
        put(pageContent, text(getString(R.string.weberin_news_source_missing), 24, WeberinUi.MUTED, false), x, 382, 1000, 64);
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
            homeAction = xbox; put(pageContent, xbox, x, 616, 165, 66); break;
        }
    }
    private boolean leftWeather() {
        return preferences.getBoolean("weberin_show_weather", preferences.getBoolean("weberin_home_full", false))
                && "left".equals(preferences.getString("weberin_weather_position", "right"));
    }
    private void addWeatherPanel() {
        weatherPanel = new FrameLayout(this);
        float scale = Math.max(80, Math.min(120, preferences.getInt("weberin_weather_size", 100))) / 100f;
        weatherPanel.setPivotX(0); weatherPanel.setPivotY(0); weatherPanel.setScaleX(scale); weatherPanel.setScaleY(scale);
        put(pageContent, weatherPanel, leftWeather() ? 96 : Math.round(1824 - 312 * scale), 290, 312, 330);
        updateWeatherPanel(); requestWeatherIfNeeded();
    }
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
        scene.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scene.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        List<ResolveInfo> ordered = orderedApps();
        settingsPanel = new WeberinSettings(this, ordered); overlay = settingsPanel.create(); put(modalHost, overlay, 0, 0, 1920, 1080);
    }
    @Override public void onBackPressed() {
        if (favoritesEditor != null) { favoritesEditor.handleBack(); return; }
        if (settingsPanel != null) { settingsPanel.handleBack(); return; }
        if (page != 0) { focusedTab = -1; selectPage(0); if (!cards.isEmpty()) cards.get(0).requestFocus(); else tabs[0].requestFocus(); }
        else moveTaskToBack(true);
    }
    @Override public Activity activity() { return this; }
    @Override public SharedPreferences preferences() { return preferences; }
    @Override public Config config() { return config; }
    @Override public String appLabel(ResolveInfo app) { return launch.label(app); }
    @Override public String label(ResolveInfo app) { return launch.label(app); }
    @Override public void bindAppIcon(ResolveInfo app, ImageView target) {
        String pkg = app.activityInfo.packageName; target.setTag(pkg);
        Drawable cached = icons.get(pkg); if (cached != null) { target.setImageDrawable(cached); return; }
        final java.lang.ref.WeakReference<ImageView> reference = new java.lang.ref.WeakReference<>(target);
        artworkExecutor.execute(() -> {
            Drawable icon = null;
            try {
                Drawable source = app.loadIcon(getPackageManager());
                Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                source.setBounds(0, 0, 96, 96); source.draw(new android.graphics.Canvas(bitmap));
                icon = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
            } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Drawable result = icon;
            runOnUiThread(() -> {
                if (isDestroyed() || result == null) return;
                icons.put(pkg, result); ImageView view = reference.get();
                if (view != null && pkg.equals(view.getTag())) view.setImageDrawable(result);
            });
        });
    }
    private void bindBanner(ResolveInfo app, ImageView target, View fallback) {
        String pkg = app.activityInfo.packageName; target.setTag(pkg);
        Drawable cached = banners.get(pkg);
        if (cached != null) { target.setImageDrawable(cached); fallback.setVisibility(View.INVISIBLE); return; }
        if (missingBanners.contains(pkg)) return;
        int generation = catalogGeneration; WeberinLaunch resolver = launch;
        final java.lang.ref.WeakReference<ImageView> reference = new java.lang.ref.WeakReference<>(target);
        final java.lang.ref.WeakReference<View> placeholder = new java.lang.ref.WeakReference<>(fallback);
        artworkExecutor.execute(() -> {
            Drawable banner = null;
            try {
                Drawable source = resolver.banner(app);
                if (source != null) {
                    int w = Math.max(1, source.getIntrinsicWidth()), h = Math.max(1, source.getIntrinsicHeight());
                    float factor = Math.min(1f, Math.min(640f / w, 360f / h));
                    w = Math.max(1, Math.round(w * factor)); h = Math.max(1, Math.round(h * factor));
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    source.setBounds(0, 0, w, h); source.draw(new android.graphics.Canvas(bitmap));
                    banner = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
                }
            } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Drawable result = banner;
            runOnUiThread(() -> {
                if (isDestroyed() || generation != catalogGeneration) return;
                if (result == null) { missingBanners.add(pkg); return; }
                banners.put(pkg, result); ImageView view = reference.get(); View behind = placeholder.get();
                if (view != null && pkg.equals(view.getTag())) {
                    view.setImageDrawable(result); if (behind != null) behind.setVisibility(View.INVISIBLE);
                }
            });
        });
    }
    @Override public void onSettingsChanged() {
        sceneNeedsRender = true;
        WeberinDeviceControls.applyWindowBrightness(this, preferences);
    }
    @Override public void onCloseSettings() {
        if (overlay != null) modalHost.removeView(overlay); overlay = null; settingsPanel = null;
        scene.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scene.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        settingsButton.requestFocus();
        refreshCatalog();
    }
    @Override public void onFavoritesEditor() {
        if (favoritesEditor != null) return;
        if (overlay != null) { settingsFocusBeforeEditor = overlay.findFocus(); overlay.setVisibility(View.GONE); }
        scene.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        List<String> selected = new ArrayList<>(); for (ResolveInfo app : favorites()) selected.add(app.activityInfo.packageName);
        favoritesEditor = new WeberinFavoritesEditor(this, apps, selected); favoritesOverlay = favoritesEditor.create();
        put(modalHost, favoritesOverlay, 0, 0, 1920, 1080);
    }
    @Override public void onSaveFavorites(List<String> packages) {
        preferences.edit().putStringSet(FAVORITES, new HashSet<>(packages))
                .putString(FAVORITE_ORDER, new org.json.JSONArray(packages).toString()).apply();
        cachedFavorites = null; cachedOrdered = null; sceneNeedsRender = true;
        closeFavoritesEditor(true);
    }
    @Override public void onCancelFavorites() { closeFavoritesEditor(false); }
    private void closeFavoritesEditor(boolean saved) {
        if (favoritesOverlay != null) modalHost.removeView(favoritesOverlay); favoritesOverlay = null; favoritesEditor = null;
        if (overlay != null) {
            overlay.setVisibility(View.VISIBLE);
            if (settingsFocusBeforeEditor != null) settingsFocusBeforeEditor.requestFocus();
        } else {
            scene.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (saved) populatePage();
            if (!restoreCardFocus() && !cards.isEmpty()) cards.get(0).requestFocus();
        }
        settingsFocusBeforeEditor = null;
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
    @Override public void onReleaseLauncherMemory() {
        banners.clear(); missingBanners.clear(); icons.evictAll(); releaseHiddenPages();
    }
    private void releaseHiddenPages() {
        if (pagesHost == null) return;
        for (int i = 0; i < pages.length; i++) if (i != page && pages[i] != null) {
            pagesHost.removeView(pages[i].view); pages[i] = null;
        }
    }
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
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_CRITICAL) onReleaseLauncherMemory();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(packages); weatherGeneration++; weatherExecutor.shutdownNow(); catalogExecutor.shutdownNow(); artworkExecutor.shutdownNow();
        startup.close(); banners.clear(); icons.evictAll(); wallpaper = null; super.onDestroy();
    }
}
