package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class WeberinFavoritesTest {
    @Test public void openingAnEditorPreservesOrderAndIgnoresUnavailableAndDuplicateEntries() {
        WeberinFavorites model = new WeberinFavorites(Arrays.asList("a", "b", "c"), Arrays.asList("b", "gone", "a", "b"));
        assertEquals(Arrays.asList("b", "a"), model.packages());
    }

    @Test public void editsDoNotMutateTheOwnersSavedListsOrPreviouslyObtainedSnapshots() {
        List<String> saved = new ArrayList<>(Arrays.asList("a", "b"));
        WeberinFavorites model = new WeberinFavorites(Arrays.asList("a", "b", "c"), saved);
        List<String> snapshot = model.packages();
        model.toggle("c"); model.move("c", -1); model.toggle("a");
        assertEquals(Arrays.asList("a", "b"), saved);
        assertEquals(Arrays.asList("a", "b"), snapshot);
        assertEquals(Arrays.asList("c", "b"), model.packages());
    }

    @Test public void choosingNoAppsIsAnExplicitEmptySelectionInsteadOfRefillingDefaults() {
        WeberinFavorites model = new WeberinFavorites(Arrays.asList("a", "b"), Arrays.asList("a", "b"));
        model.toggle("a"); model.toggle("b");
        assertTrue(model.packages().isEmpty());
        model.toggle("b"); model.toggle("a");
        assertEquals(Arrays.asList("b", "a"), model.packages());
    }

    @Test public void movingAChosenAppKeepsOtherAppsRelativeOrderAndStopsAtEachEdge() {
        WeberinFavorites model = new WeberinFavorites(Arrays.asList("a", "b", "c", "d"), Arrays.asList("a", "b", "c", "d"));
        assertFalse(model.move("a", -1)); assertFalse(model.move("d", 1));
        assertTrue(model.move("d", -1)); assertTrue(model.move("d", -1));
        assertEquals(Arrays.asList("a", "d", "b", "c"), model.packages());
        assertFalse(model.move("missing", -1)); assertFalse(model.toggle("missing")); assertFalse(model.move("a", 0));
        assertEquals(Arrays.asList("a", "d", "b", "c"), model.packages());
    }

    @Test(expected = UnsupportedOperationException.class) public void aSaveSnapshotCannotBeChangedByTheCaller() {
        WeberinFavorites model = new WeberinFavorites(Collections.singletonList("a"), Collections.singletonList("a"));
        model.packages().clear();
    }
}
