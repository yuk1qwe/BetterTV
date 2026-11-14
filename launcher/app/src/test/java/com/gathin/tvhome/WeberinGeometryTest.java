package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WeberinGeometryTest {
    @Test public void referenceAndCommonTvResolutionsKeepTheWholeDesignVisible() {
        assertEquals(1f, WeberinGeometry.scale(1920, 1080), .00001f);
        assertEquals(2f, WeberinGeometry.scale(3840, 2160), .00001f);
        assertEquals(2f / 3, WeberinGeometry.scale(1280, 720), .00001f);
        int[][] displays = {{1280, 1024}, {2560, 1080}, {1920, 1200}, {960, 540}, {640, 480}};
        for (int[] display : displays) {
            float scale = WeberinGeometry.scale(display[0], display[1]);
            float width = WeberinGeometry.WIDTH * scale;
            float height = WeberinGeometry.HEIGHT * scale;
            assertTrue("horizontal content fits", width <= display[0] + .001f);
            assertTrue("vertical content fits", height <= display[1] + .001f);
            assertTrue("one dimension uses the display", Math.abs(width - display[0]) < .001f
                    || Math.abs(height - display[1]) < .001f);
        }
    }

    @Test public void earlyZeroSizeLayoutStillHasAFiniteScale() {
        float scale = WeberinGeometry.scale(0, 0);
        assertTrue(Float.isFinite(scale));
        assertTrue(scale > 0);
    }

    @Test public void fiveColumnCardsFollowTheSupplied1920PixelLayout() {
        assertEquals(270, WeberinGeometry.gridCardWidth(5));
        int[] referenceX = {36, 400, 765, 1129, 1494};
        for (int column = 0; column < referenceX.length; column++) {
            assertEquals("reference column=" + column, referenceX[column],
                    WeberinGeometry.gridX(column, 5), 1);
        }
    }

    @Test public void allSupportedColumnCountsLeaveSpaceForFocusRings() {
        for (int columns = 4; columns <= 7; columns++) {
            int width = WeberinGeometry.gridCardWidth(columns);
            assertTrue(width > 0);
            int previousEnd = -1;
            for (int column = 0; column < columns; column++) {
                int x = WeberinGeometry.gridX(column, columns);
                assertTrue("focus fits at the left", x >= WeberinGeometry.FOCUS_GAP);
                assertTrue("focus fits at the right", x + width + WeberinGeometry.FOCUS_GAP <= 1800);
                if (previousEnd >= 0) assertTrue("adjacent focus rings do not overlap",
                        x - previousEnd >= WeberinGeometry.FOCUS_GAP * 2);
                previousEnd = x + width;
            }
        }
        assertEquals(4, WeberinGeometry.columns(Integer.MIN_VALUE));
        assertEquals(7, WeberinGeometry.columns(Integer.MAX_VALUE));
    }

    @Test public void emptyCatalogHasNoCardToFocus() {
        for (int direction : new int[]{-2, -1, 1, 2}) {
            assertEquals(-1, WeberinGeometry.neighbour(0, direction, 5, 0));
        }
    }

    @Test public void remoteNavigationMatchesRowsForSparseAndFullCatalogs() {
        for (int columns = 4; columns <= 7; columns++) {
            for (int count = 1; count <= 50; count++) {
                List<List<Integer>> rows = rows(count, columns);
                for (int row = 0; row < rows.size(); row++) {
                    List<Integer> current = rows.get(row);
                    for (int column = 0; column < current.size(); column++) {
                        int index = current.get(column);
                        assertNeighbour(current.get(Math.max(0, column - 1)), index, -1, columns, count);
                        assertNeighbour(current.get(Math.min(current.size() - 1, column + 1)), index, 1, columns, count);
                        assertNeighbour(row == 0 ? -1 : rows.get(row - 1).get(column), index, -2, columns, count);
                        int below = row + 1 == rows.size() ? index
                                : rows.get(row + 1).get(Math.min(column, rows.get(row + 1).size() - 1));
                        assertNeighbour(below, index, 2, columns, count);
                    }
                }
            }
        }
    }

    @Test public void everyInstalledAppIsReachableWithTheRemote() {
        for (int columns = 4; columns <= 7; columns++) {
            for (int count = 1; count <= 50; count++) {
                Set<Integer> reached = new HashSet<>();
                Queue<Integer> pending = new ArrayDeque<>();
                pending.add(0);
                while (!pending.isEmpty()) {
                    int index = pending.remove();
                    if (!reached.add(index)) continue;
                    for (int direction : new int[]{-2, -1, 1, 2}) {
                        int next = WeberinGeometry.neighbour(index, direction, columns, count);
                        assertTrue("focus never goes beyond an app", next >= -1 && next < count);
                        if (next >= 0 && !reached.contains(next)) pending.add(next);
                    }
                }
                assertEquals("columns=" + columns + ", count=" + count, count, reached.size());
            }
        }
    }

    private static List<List<Integer>> rows(int count, int columns) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = null;
        for (int index = 0; index < count; index++) {
            if (row == null || row.size() == columns) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(index);
        }
        return rows;
    }

    private static void assertNeighbour(int expected, int index, int direction, int columns, int count) {
        assertEquals("index=" + index + ", direction=" + direction + ", columns=" + columns + ", count=" + count,
                expected, WeberinGeometry.neighbour(index, direction, columns, count));
    }
}
