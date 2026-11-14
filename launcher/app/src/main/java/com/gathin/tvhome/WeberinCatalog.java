package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Disk access, package discovery, labels and wallpaper decoding belong on the catalog executor. */
final class WeberinCatalog {
    private static final String DEFAULT_WALLPAPER = "01-windows-black-1080p.png";

    static final class Snapshot {
        final Config config;
        final WeberinLaunch launch;
        final List<ResolveInfo> apps;
        final Order originalOrder;
        final Set<String> libraryAdded, libraryRemoved;
        final String externalStamp;
        final Map<String, ?> preferences;
        final Bitmap wallpaper;
        final String wallpaperKey;
        /** A newly created snapshot differs from its predecessor. Unchanged loads return the same object. */
        final boolean changed;
        private final int displayWidth, displayHeight;

        private Snapshot(Config config, WeberinLaunch launch, List<ResolveInfo> apps, Order order,
                         Set<String> added, Set<String> removed, String stamp, Map<String, ?> preferences,
                         Bitmap wallpaper, String wallpaperKey, int width, int height) {
            this.config = config;
            this.launch = launch;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            originalOrder = order;
            libraryAdded = Collections.unmodifiableSet(new HashSet<>(added));
            libraryRemoved = Collections.unmodifiableSet(new HashSet<>(removed));
            externalStamp = stamp;
            this.preferences = preferences;
            this.wallpaper = wallpaper;
            this.wallpaperKey = wallpaperKey;
            displayWidth = width;
            displayHeight = height;
            changed = true;
        }
    }

    private WeberinCatalog() { }

    /** No views or callbacks here. Call only from the activity's serial loader executor. */
    static Snapshot load(Activity activity, Snapshot previous, boolean packagesChanged) {
        SharedPreferences settings = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> before = copyPreferences(settings.getAll());
        String stamp = externalStamp(activity);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels), height = Math.max(1, metrics.heightPixels);
        if (previous != null && !packagesChanged && previous.preferences.equals(before)
                && previous.externalStamp.equals(stamp) && previous.displayWidth == width && previous.displayHeight == height
                && previous.wallpaperKey.equals(wallpaperKey(previous.config, before, width, height))) return previous;

        Config config = Config.load(activity);
        WeberinLaunch launch = new WeberinLaunch(activity, config);
        boolean queryPackages = previous == null || packagesChanged
                || !config.libraryApps.equals(previous.config.libraryApps)
                || !config.libraryExcludedApps.equals(previous.config.libraryExcludedApps);
        List<ResolveInfo> apps = queryPackages ? queryApps(activity, config) : new ArrayList<>(previous.apps);
        // The first lookup warms the launcher's label cache, so sorting and later focus never call PM.
        for (ResolveInfo app : apps) launch.label(app);
        Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(launch.label(a), launch.label(b)));
        Order order = Order.load(Order.file(activity));
        Set<String> added = LibraryApps.load(activity), removed = LibraryApps.loadRemoved(activity);
        // Config.load may update revision preferences while accepting an external theme.
        Map<String, ?> after = copyPreferences(settings.getAll());
        String key = wallpaperKey(config, after, width, height);
        Bitmap wallpaper = previous != null && key.equals(previous.wallpaperKey)
                && previous.wallpaper != null && !previous.wallpaper.isRecycled()
                ? previous.wallpaper : decodeWallpaper(activity, config, after, width, height);
        return new Snapshot(config, launch, apps, order, added, removed, externalStamp(activity), after,
                wallpaper, key, width, height);
    }

    static String externalStamp(Context context) {
        StringBuilder stamp = new StringBuilder();
        File external = context.getExternalFilesDir(null), internal = context.getFilesDir();
        if (external != null) {
            append(stamp, new File(external, Config.FILE_NAME));
            append(stamp, new File(external, "theme_revision"));
            append(stamp, new File(external, Config.WALLPAPER_NAME));
            append(stamp, new File(external, "wallpapers"));
        } else stamp.append("unmounted;");
        for (String name : new String[]{Config.FILE_NAME, Order.FILE_NAME, "library_apps.txt", "library_home_removed.txt"}) {
            append(stamp, new File(internal, name));
            append(stamp, new File(internal, name + ".bak"));
        }
        return stamp.toString();
    }

    private static void append(StringBuilder stamp, File file) {
        // No absolute paths or file contents are exposed by the metadata stamp.
        stamp.append(file.exists() ? '1' : '0').append(':').append(file.lastModified())
                .append(':').append(file.length()).append(';');
    }

    private static Map<String, ?> copyPreferences(Map<String, ?> source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = Collections.unmodifiableSet(new HashSet<>((Set<?>) value));
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<ResolveInfo> queryApps(Activity activity, Config config) {
        PackageManager pm = activity.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
            for (ResolveInfo app : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0))
                addApp(found, app, activity.getPackageName(), config);
        for (String pkg : config.libraryApps) {
            Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
            if (intent != null) addApp(found, pm.resolveActivity(intent, 0), activity.getPackageName(), config);
        }
        return new ArrayList<>(found.values());
    }

    private static void addApp(Map<String, ResolveInfo> found, ResolveInfo app, String self, Config config) {
        if (app == null || app.activityInfo == null || !app.activityInfo.exported || !app.activityInfo.enabled) return;
        String pkg = app.activityInfo.packageName;
        if (pkg.equals(self) || config.libraryExcludedApps.contains(pkg)) return;
        found.putIfAbsent(pkg, app);
    }

    private static File themeWallpaper(Config config) {
        return config.wallpaper != null ? config.wallpaper : config.wallpapers.isEmpty() ? null : config.wallpapers.get(0);
    }

    private static String selected(Map<String, ?> preferences) {
        Object selected = preferences.get("weberin_wallpaper");
        return selected instanceof String ? (String) selected : DEFAULT_WALLPAPER;
    }

    private static String wallpaperKey(Config config, Map<String, ?> preferences, int width, int height) {
        String selected = selected(preferences);
        File theme = themeWallpaper(config);
        String identity = "theme".equals(selected) && theme != null
                ? "file:" + theme.getAbsolutePath() + ':' + theme.lastModified() + ':' + theme.length() : selected;
        return identity + ':' + width + 'x' + height;
    }

    private static Bitmap decodeWallpaper(Activity activity, Config config, Map<String, ?> preferences, int width, int height) {
        String selected = selected(preferences);
        File file = themeWallpaper(config);
        if ("none".equals(selected) || "theme".equals(selected) && file == null) return null;
        if (!"theme".equals(selected) && (selected.contains("/") || selected.contains(".."))) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            if ("theme".equals(selected)) BitmapFactory.decodeFile(file.getPath(), options);
            else try (InputStream input = activity.getAssets().open("weberin/" + selected)) { BitmapFactory.decodeStream(input, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight, width, height);
            if (!WallpaperSize.accepted(options.outWidth / options.inSampleSize, options.outHeight / options.inSampleSize)) return null;
            options.inJustDecodeBounds = false;
            if ("theme".equals(selected)) return BitmapFactory.decodeFile(file.getPath(), options);
            try (InputStream input = activity.getAssets().open("weberin/" + selected)) { return BitmapFactory.decodeStream(input, null, options); }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) { return null; }
    }
}
