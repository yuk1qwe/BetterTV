package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** App artwork and launch routes use the same theme choices as the original home. */
final class WeberinLaunch {
    private final Activity activity;
    private final Config config;
    private final Set<String> iconTiles;

    WeberinLaunch(Activity activity, Config config) {
        this.activity = activity;
        this.config = config;
        iconTiles = new HashSet<>(Arrays.asList(activity.getResources().getStringArray(R.array.icon_tiles)));
    }

    String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String value = config.appLabels.containsKey(pkg) ? config.appLabels.get(pkg)
                : String.valueOf(app.loadLabel(activity.getPackageManager()));
        return Cards.cleanLabel(value, pkg);
    }

    /** Null means the caller should draw the real app icon and its label. */
    Drawable banner(ResolveInfo app) {
        if (config.uniformTiles) return null;
        String pkg = app.activityInfo.packageName;
        String override = config.appBanners.get(pkg);
        if (override != null && override.startsWith("banner_")) {
            int id = activity.getResources().getIdentifier(override, "drawable", activity.getPackageName());
            if (id != 0) return activity.getDrawable(id);
        }
        PackageManager pm = activity.getPackageManager();
        if (iconTiles.contains(pkg)) {
            Drawable icon = app.loadIcon(pm);
            Bitmap image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(0xff15202b);
            icon.setBounds(108, 38, 212, 142);
            icon.draw(canvas);
            return new BitmapDrawable(activity.getResources(), image);
        }
        int id = app.activityInfo.banner;
        if (id == 0) id = app.activityInfo.applicationInfo.banner;
        Drawable result = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(app.activityInfo.applicationInfo);
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH,
                        activity.getResources().getDisplayMetrics().densityDpi);
                result = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) { }
        }
        if (result == null) result = app.activityInfo.loadBanner(pm);
        return result != null && AppAdapter.isBannerShaped(result.getIntrinsicWidth(), result.getIntrinsicHeight())
                ? result : null;
    }

    void open(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String action = config.launchActions.get(pkg);
        String input = LaunchActions.inputId(action);
        if (input != null) {
            openInput(input);
            return;
        }
        ComponentName component = new ComponentName(pkg, app.activityInfo.name);
        Intent launch = new Intent(Intent.ACTION_MAIN).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Intent leanback = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (leanback != null && component.equals(leanback.getComponent())) {
            launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        }
        if (action != null) {
            Intent custom = LaunchActions.intent(pkg, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (activity.getPackageManager().resolveActivity(custom, 0) != null) launch = custom;
        }
        if (tryOpen(launch)) LastOpened.record(activity, pkg);
        else unavailable();
    }

    void openIntent(Intent intent) {
        if (!tryOpen(intent)) unavailable();
    }

    /** Keeps the vendor input mapping, URI scheme and TV-package preference used by MainActivity. */
    void openInput(String inputId) {
        String sourceId = inputId;
        TvInputManager manager = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager != null) {
            try {
                TvInputInfo info = manager.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) { }
        }
        Integer source = InputSources.mapped(config.inputSources, sourceId);
        if (source != null && source >= 0 && !config.inputAction.isEmpty()) {
            if (tryOpen(inputActionIntent().putExtra("input", source.intValue()))) return;
        } else if (source == null && InputSources.isComponent(config.inputAction)) {
            if (tryOpen(inputActionIntent())) return;
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = config.inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(config.inputUriScheme).build());
        if (!config.liveTvPackage.isEmpty() && tryOpen(new Intent(view).setPackage(config.liveTvPackage))) return;
        if (tryOpen(view)) return;
        if (!config.inputAction.isEmpty()
                && tryOpen(inputActionIntent().setDataAndType(uri, "vnd.android.cursor.item/channel"))) return;
        unavailable();
    }

    private Intent inputActionIntent() {
        ComponentName component = InputSources.isComponent(config.inputAction)
                ? ComponentName.unflattenFromString(config.inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(config.inputAction);
    }

    private boolean tryOpen(Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private void unavailable() {
        Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
    }
}
