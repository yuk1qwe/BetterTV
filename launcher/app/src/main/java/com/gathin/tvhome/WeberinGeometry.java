package com.gathin.tvhome;

/** The supplied artwork is 1920 by 1080; UI units are artwork pixels, independent of TV density. */
final class WeberinGeometry {
    static final int WIDTH = 1920, HEIGHT = 1080;
    static final int CARD_WIDTH = 270, CARD_HEIGHT = 156, FOCUS_GAP = 8;
    private WeberinGeometry() { }

    static float scale(int width, int height) {
        return Math.min(Math.max(1, width) / (float) WIDTH, Math.max(1, height) / (float) HEIGHT);
    }

    static int columns(int requested) { return Math.max(4, Math.min(7, requested)); }

    static int gridCardWidth(int columns) { return columns <= 5 ? CARD_WIDTH : (1728 - (columns - 1) * 32) / columns; }

    static int gridX(int index, int columns) {
        return gridX(index, columns, gridCardWidth(columns));
    }
    static int gridX(int index, int columns, int width) {
        return 36 + (int) (index * (1728f - width) / (columns - 1));
    }

    /** Incomplete last rows still have a usable vertical neighbour. */
    static int neighbour(int index, int direction, int columns, int count) {
        if (count <= 0) return -1;
        if (direction == -1) return index % columns == 0 ? index : index - 1;
        if (direction == 1) return index % columns == columns - 1 || index + 1 >= count ? index : index + 1;
        if (direction < 0) return index < columns ? -1 : index - columns;
        return index + columns < count ? index + columns : index / columns < (count - 1) / columns ? count - 1 : index;
    }
}
