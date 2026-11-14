package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Remote-first selection and ordering, committed only by the visible Save button. */
final class WeberinFavoritesEditor {
    interface Host {
        Activity activity();
        String label(ResolveInfo app);
        void onSaveFavorites(List<String> packages);
        void onCancelFavorites();
    }

    private final Host host;
    private final Activity activity;
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final List<TextView> leftRows = new ArrayList<>(), rightRows = new ArrayList<>();
    private final WeberinFavorites model;
    private LinearLayout leftList, rightList;
    private TextView save, cancel, count, hint, empty;
    private int lastLeft, lastRight;
    private String movingPackage;

    WeberinFavoritesEditor(Host host, List<ResolveInfo> installed, List<String> selected) {
        this.host = host;
        activity = host.activity();
        for (ResolveInfo app : installed) {
            String pkg = app.activityInfo.packageName;
            if (!labels.containsKey(pkg)) labels.put(pkg, host.label(app));
        }
        model = new WeberinFavorites(labels.keySet(), selected);
    }

    FrameLayout create() {
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xbb000000);
        FrameLayout panel = new FrameLayout(activity);
        panel.setBackground(WeberinUi.round(0xff0b0b0d, 0xff66686e, 32));
        put(overlay, panel, 160, 100, 1600, 880);
        put(panel, text(R.string.wf_title, 34, true), 40, 28, 760, 52);
        count = text("", 22, false);
        put(panel, count, 850, 38, 350, 40);
        save = button(R.string.wf_save, () -> host.onSaveFavorites(model.packages()));
        cancel = button(R.string.wf_cancel, host::onCancelFavorites);
        put(panel, save, 1290, 28, 126, 54);
        put(panel, cancel, 1434, 28, 126, 54);
        put(panel, text(R.string.wf_hint, 19, false), 40, 95, 1520, 38);
        put(panel, text(R.string.wf_all_apps, 25, true), 40, 150, 716, 42);
        put(panel, text(R.string.wf_home_order, 25, true), 824, 150, 736, 42);
        leftList = column(panel, 40, 204, 716, 600);
        rightList = column(panel, 824, 204, 736, 600);
        hint = text("", 18, false);
        hint.setTextColor(WeberinUi.MUTED);
        put(panel, hint, 40, 820, 1520, 32);

        for (Map.Entry<String, String> entry : labels.entrySet()) {
            String pkg = entry.getKey();
            TextView row = row("", pkg);
            row.setOnClickListener(view -> toggle(pkg));
            row.setOnKeyListener((view, key, event) -> leftKey(row, key, event));
            row.setOnFocusChangeListener((view, focus) -> {
                if (focus) lastLeft = leftRows.indexOf(row);
                style(row, focus);
            });
            leftRows.add(row);
            leftList.addView(row, rowParams(716));
            updateLeft(row);
        }
        if (leftRows.isEmpty()) leftList.addView(text(R.string.weberin_no_apps, 22, false));
        rebuildRight();
        updateCountAndHint();
        save.setOnKeyListener((view, key, event) -> headerKey(false, key, event));
        cancel.setOnKeyListener((view, key, event) -> headerKey(true, key, event));
        overlay.post(() -> { if (leftRows.isEmpty()) cancel.requestFocus(); else leftRows.get(0).requestFocus(); });
        return overlay;
    }

    boolean handleBack() { host.onCancelFavorites(); return true; }

    private LinearLayout column(FrameLayout panel, int x, int y, int width, int height) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(true);
        scroll.setPadding(0, 2, 0, 2);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, 12);
        scroll.addView(list, new ScrollView.LayoutParams(width, -2));
        put(panel, scroll, x, y, width, height);
        return list;
    }

    private TextView button(int label, Runnable action) {
        TextView view = text(label, 21, true);
        view.setGravity(Gravity.CENTER);
        view.setFocusableInTouchMode(true);
        view.setOnClickListener(ignored -> action.run());
        view.setOnFocusChangeListener((ignored, focus) -> style(view, focus));
        style(view, false);
        return view;
    }

    private TextView row(String name, String pkg) {
        TextView row = text(name, 22, false);
        row.setFocusableInTouchMode(true);
        row.setTag(pkg);
        row.setPadding(18, 0, 18, 0);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        style(row, false);
        return row;
    }

    private void toggle(String pkg) {
        if (!model.toggle(pkg)) return;
        if (pkg.equals(movingPackage) && !model.contains(pkg)) movingPackage = null;
        for (TextView row : leftRows) if (pkg.equals(row.getTag())) { updateLeft(row); break; }
        rebuildRight();
        updateCountAndHint();
    }

    private void updateLeft(TextView row) {
        String pkg = (String) row.getTag();
        boolean selected = model.contains(pkg);
        row.setText((selected ? "✓  " : "＋  ") + labels.get(pkg));
        row.setContentDescription(labels.get(pkg) + ", " + activity.getString(selected ? R.string.wf_selected : R.string.wf_not_selected));
    }

    private void rebuildRight() {
        rightList.removeAllViews(); rightRows.clear(); empty = null;
        List<String> selected = model.packages();
        for (String pkg : selected) {
            TextView row = row("", pkg);
            row.setOnClickListener(view -> {
                String current = (String) row.getTag();
                movingPackage = current.equals(movingPackage) ? null : current;
                bindRightRows(); updateCountAndHint();
            });
            row.setOnKeyListener((view, key, event) -> rightKey(row, key, event));
            row.setOnFocusChangeListener((view, focus) -> {
                if (focus) lastRight = rightRows.indexOf(row);
                style(row, focus);
            });
            rightRows.add(row); rightList.addView(row, rowParams(736));
        }
        if (selected.isEmpty()) {
            empty = text(R.string.wf_empty, 22, false);
            empty.setTextColor(WeberinUi.MUTED);
            empty.setPadding(18, 16, 18, 16);
            rightList.addView(empty);
        }
        bindRightRows();
    }

    private void bindRightRows() {
        List<String> packages = model.packages();
        for (int i = 0; i < rightRows.size(); i++) {
            TextView row = rightRows.get(i); String pkg = packages.get(i);
            row.setTag(pkg);
            row.setText((pkg.equals(movingPackage) ? "↕  " : (i + 1) + ".  ") + labels.get(pkg));
            row.setContentDescription((i + 1) + ". " + labels.get(pkg));
            style(row, row.hasFocus());
        }
    }

    private boolean leftKey(TextView row, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = leftRows.indexOf(row);
        if (key == KeyEvent.KEYCODE_DPAD_UP) { if (index == 0) save.requestFocus(); else leftRows.get(index - 1).requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { leftRows.get(Math.min(leftRows.size() - 1, index + 1)).requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int selected = model.indexOf((String) row.getTag());
            if (rightRows.isEmpty()) save.requestFocus(); else rightRows.get(Math.max(0, selected)).requestFocus();
            return true;
        }
        return false;
    }

    private boolean rightKey(TextView row, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = rightRows.indexOf(row);
        if (movingPackage != null) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                int delta = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                if (model.move(movingPackage, delta)) {
                    bindRightRows(); rightRows.get(index + delta).requestFocus();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) { if (index == 0) save.requestFocus(); else rightRows.get(index - 1).requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { rightRows.get(Math.min(rightRows.size() - 1, index + 1)).requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (!leftRows.isEmpty()) leftRows.get(Math.min(lastLeft, leftRows.size() - 1)).requestFocus(); return true; }
        return key == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private boolean headerKey(boolean onCancel, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { save.requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { cancel.requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (onCancel && !rightRows.isEmpty()) rightRows.get(Math.min(lastRight, rightRows.size() - 1)).requestFocus();
            else if (!leftRows.isEmpty()) leftRows.get(Math.min(lastLeft, leftRows.size() - 1)).requestFocus();
            return true;
        }
        return false;
    }

    private void updateCountAndHint() {
        count.setText(activity.getString(R.string.wf_count, model.size()));
        hint.setText(movingPackage == null ? R.string.wf_order_hint : R.string.wf_move_hint);
    }

    private void style(TextView row, boolean focus) {
        boolean moving = row.getTag() != null && row.getTag().equals(movingPackage);
        row.setTextColor(focus ? Color.BLACK : WeberinUi.WHITE);
        row.setBackground(WeberinUi.round(focus ? 0xfff1f2f5 : moving ? 0xff24313e : 0xff141417,
                moving ? 0xffabcde7 : focus ? 0xfff1f2f5 : 0xff2c2d32, 12));
    }

    private LinearLayout.LayoutParams rowParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, 64);
        params.bottomMargin = 10; return params;
    }
    private TextView text(int resource, int size, boolean bold) { return text(activity.getString(resource), size, bold); }
    private TextView text(String value, int size, boolean bold) { return WeberinUi.text(activity, value, size, WeberinUi.WHITE, bold); }
    private void put(FrameLayout parent, View view, int x, int y, int width, int height) { WeberinUi.put(parent, view, x, y, width, height); }
}
