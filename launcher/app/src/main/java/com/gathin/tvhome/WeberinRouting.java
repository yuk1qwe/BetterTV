package com.gathin.tvhome;

/** Only explicit requests for the original home need to construct its full presentation. */
final class WeberinRouting {
    private WeberinRouting() { }

    static boolean originalAction(String action) {
        return MainActivity.ACTION_LAUNCHER_SETTINGS.equals(action)
                || MainActivity.ACTION_APP_LIBRARY.equals(action)
                || MainActivity.ACTION_VIEW_INPUTS.equals(action);
    }

    static boolean initializeOriginal(boolean preferWeberin, String action, boolean reorder) {
        return !preferWeberin || reorder || originalAction(action);
    }
}
