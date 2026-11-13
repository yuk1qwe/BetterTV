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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        page = state == null ? 0 : state.getInt("page", 0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        loadApps();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (preferences != null && !preferences.getBoolean("weberin_layout", false)) finish();
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
        if (config.wallpaper != null) {
            ImageView background = new ImageView(this);
            background.setImageURI(android.net.Uri.fromFile(config.wallpaper));
            background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(40), dp(24), dp(40), dp(24));
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("Weberin TV", 23, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        int[] labels = {R.string.weberin_home, R.string.weberin_apps, R.string.weberin_channels};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            TextView tab = button(getString(labels[i]), () -> { page = target; focusedPackage = null; render(); });
            if (i == page) tab.setBackground(shape(0xff444444, 0xffaaaaaa, 14));
            header.addView(tab, new LinearLayout.LayoutParams(-2, dp(46)));
        }
        TextView settings = button("⚙", () -> startActivity(new Intent(this, LauncherSettingsActivity.class)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(54), dp(46));
        settingsParams.setMargins(dp(24), 0, dp(16), 0);
        header.addView(settings, settingsParams);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm a");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(22);
        if (preferences.getBoolean("weberin_show_clock", true)) {
            LinearLayout clockBlock = new LinearLayout(this);
            clockBlock.setOrientation(LinearLayout.VERTICAL);
            clockBlock.addView(clock);
            TextClock date = new TextClock(this);
            date.setFormat12Hour("EEE, d MMMM");
            date.setFormat24Hour("EEE, d MMMM");
            date.setTextColor(0xffaeb6c5);
            date.setTextSize(11);
            clockBlock.addView(date);
            header.addView(clockBlock);
        }
        screen.addView(header);
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
            addTitle(R.string.weberin_my_apps);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            List<ResolveInfo> favorites = favorites();
            for (ResolveInfo app : favorites) row.addView(tile(app));
            TextView all = button(getString(R.string.weberin_all_apps), () -> { page = 1; render(); });
            row.addView(all, new LinearLayout.LayoutParams(dp(160), dp(100)));
            scroll.addView(row);
            content.addView(scroll);
        } else {
            addTitle(R.string.weberin_apps);
            ScrollView scroll = new ScrollView(this);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(5);
            for (ResolveInfo app : apps) grid.addView(tile(app));
            if (apps.isEmpty()) grid.addView(label(getString(R.string.weberin_no_apps), 18, Color.WHITE));
            scroll.addView(grid);
            content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
        for (ResolveInfo app : apps) {
            if (saved == null ? result.size() < 5 : saved.contains(app.activityInfo.packageName)) result.add(app);
        }
        return result;
    }

    private View tile(ResolveInfo app) {
        String name = app.loadLabel(getPackageManager()).toString();
        TextView tile = button(name, () -> {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        android.graphics.drawable.Drawable icon = app.loadIcon(getPackageManager());
        if (icon != null) {
            icon.setBounds(0, 0, dp(38), dp(38));
            tile.setCompoundDrawables(null, icon, null, null);
            tile.setCompoundDrawablePadding(dp(5));
        }
        tile.setSingleLine(false);
        tile.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.setOnLongClickListener(v -> {
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
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.setTextColor(focused ? Color.BLACK : Color.WHITE);
            tile.setBackground(shape(focused ? Color.WHITE : 0xff101010,
                    focused ? Color.WHITE : 0xff353535, 14));
            if (focused && !restoringFocus) focusedPackage = app.activityInfo.packageName;
        });
        if (app.activityInfo.packageName.equals(focusedPackage)) {
            tile.post(() -> { restoringFocus = true; tile.requestFocus(); restoringFocus = false; });
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(160), dp(100));
        params.setMargins(0, dp(8), dp(12), dp(8));
        tile.setLayoutParams(params);
        return tile;
    }
}
