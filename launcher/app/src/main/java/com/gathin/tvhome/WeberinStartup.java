package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Keeps the original opt-in boot and wake app choices working in the alternate home. */
final class WeberinStartup {
    interface Host {
        void openStartupIntent(Intent intent);
        void openStartupInput(String inputId);
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config config;
    private Intent pending;
    private long deadline;
    private boolean wakeRegistered;
    private boolean networkRegistered;
    private boolean closed;

    private final Runnable whenOnline = this::openPending;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post(whenOnline);
        }
    };
    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // MainActivity retains its own receiver for the original interface. Only the selected home acts.
            if (closed || !activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("weberin_layout", true)) return;
            Config latest = Config.load(activity);
            if (!OpenOnStart.onWake(latest.openOnStartChoice, latest.openOnStartOnWake)) return;
            config = latest;
            String app = choices().getString(OpenOnStart.KEY, "");
            if (app.isEmpty() || app.equals(activity.getPackageName())) return;
            Intent launch = OpenOnStart.intent(activity.getPackageManager(), app);
            if (launch != null) openWhenReady(launch);
        }
    };

    WeberinStartup(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void resume(Config config) {
        if (closed) return;
        this.config = config;
        boolean wakeEnabled = OpenOnStart.onWake(config.openOnStartChoice, config.openOnStartOnWake);
        if (wakeEnabled && !wakeRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(wake, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(wake, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
            wakeRegistered = true;
        } else if (!wakeEnabled && wakeRegistered) {
            activity.unregisterReceiver(wake);
            wakeRegistered = false;
        }
        String app = config.openOnStartChoice ? choices().getString(OpenOnStart.KEY, "") : "";
        if (!config.bootToLiveTv && app.isEmpty()) return;
        int boot = OpenOnStart.boot(activity);
        SharedPreferences handled = activity.getSharedPreferences(OpenOnStart.BOOT_PREFS, Context.MODE_PRIVATE);
        String target = config.bootToLiveTv ? config.liveTvPackage : app;
        if (!OpenOnStart.due(boot, handled.getInt(OpenOnStart.BOOT_KEY, -1), target, activity.getPackageName())) return;
        // Commit before opening another app: a low-memory TV may kill this process immediately afterwards.
        if (!handled.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return;
        String input = config.bootToLiveTv ? LaunchActions.inputId(config.launchActions.get(target)) : null;
        if (input != null) {
            host.openStartupInput(input);
            return;
        }
        Intent launch;
        if (config.bootToLiveTv) {
            launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(target);
            if (launch == null && !config.liveTvAction.isEmpty()) {
                Intent action = new Intent(config.liveTvAction).setPackage(target);
                if (activity.getPackageManager().resolveActivity(action, 0) != null) launch = action;
            }
        } else {
            launch = OpenOnStart.intent(activity.getPackageManager(), target);
        }
        if (launch != null) openWhenReady(launch);
    }

    private SharedPreferences choices() {
        return activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    private void openWhenReady(Intent launch) {
        cancelPending();
        if (closed) return;
        if (config.openOnStartNetworkWait <= 0 || OpenOnStart.online(activity)) {
            host.openStartupIntent(launch);
            return;
        }
        pending = launch;
        deadline = SystemClock.elapsedRealtime() + config.openOnStartNetworkWait * 1000L;
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(network);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
                // The deadline still opens the app if firmware refuses the network callback.
            }
        }
        handler.postDelayed(whenOnline, config.openOnStartNetworkWait * 1000L);
        // Close the race where connectivity returned between checking it and registering the callback.
        handler.post(whenOnline);
    }

    private void openPending() {
        if (closed || pending == null || !OpenOnStart.opensNow(OpenOnStart.online(activity),
                SystemClock.elapsedRealtime(), deadline)) return;
        Intent launch = pending;
        cancelPending();
        host.openStartupIntent(launch);
    }

    /** Leaving home cancels the deferred launch rather than interrupting the app the owner opened. */
    void pause() {
        cancelPending();
    }

    private void cancelPending() {
        pending = null;
        handler.removeCallbacks(whenOnline);
        if (networkRegistered) {
            ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(network); }
                catch (RuntimeException ignored) { }
            }
            networkRegistered = false;
        }
    }

    void close() {
        closed = true;
        cancelPending();
        if (wakeRegistered) {
            activity.unregisterReceiver(wake);
            wakeRegistered = false;
        }
    }
}
