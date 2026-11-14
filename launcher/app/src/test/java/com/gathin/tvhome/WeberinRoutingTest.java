package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WeberinRoutingTest {
    private static final String[] ORIGINAL_ACTIONS = {
            "com.gathin.tvhome.action.LAUNCHER_SETTINGS",
            "com.gathin.tvhome.action.APP_LIBRARY",
            "com.android.tv.action.VIEW_INPUTS"
    };

    @Test public void ordinaryHomeDoesNotConstructTwoPresentations() {
        assertFalse(WeberinRouting.initializeOriginal(true, null, false));
        assertFalse(WeberinRouting.initializeOriginal(true, "android.intent.action.MAIN", false));
    }

    @Test public void originalSelectionAlwaysBuildsItsOwnPresentation() {
        assertTrue(WeberinRouting.initializeOriginal(false, null, false));
        assertTrue(WeberinRouting.initializeOriginal(false, "android.intent.action.MAIN", false));
        for (String action : ORIGINAL_ACTIONS) assertTrue(WeberinRouting.initializeOriginal(false, action, false));
    }

    @Test public void explicitOriginalToolsRemainReachableFromWeberin() {
        for (String action : ORIGINAL_ACTIONS) {
            assertTrue(action, WeberinRouting.originalAction(action));
            assertTrue(action, WeberinRouting.initializeOriginal(true, action, false));
        }
    }

    @Test public void pendingReorderReachesTheEditorRegardlessOfTheHomeAction() {
        assertTrue(WeberinRouting.initializeOriginal(true, null, true));
        assertTrue(WeberinRouting.initializeOriginal(true, "android.intent.action.MAIN", true));
        for (String action : ORIGINAL_ACTIONS) assertTrue(WeberinRouting.initializeOriginal(true, action, true));
    }

    @Test public void unrelatedActionsDoNotOverrideTheSelectedPresentation() {
        assertFalse(WeberinRouting.originalAction(null));
        assertFalse(WeberinRouting.originalAction("android.intent.action.MAIN"));
        assertFalse(WeberinRouting.initializeOriginal(true, "android.intent.action.VIEW", false));
    }
}
