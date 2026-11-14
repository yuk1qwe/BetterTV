package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;

/** Removes files only below the app-provided cache directory, never following links outside it. */
final class WeberinCache {
    private WeberinCache() { }

    static long clear(File cache) {
        try {
            File root = cache.getCanonicalFile();
            return clearChildren(root, root.getPath() + File.separator);
        } catch (IOException | SecurityException ignored) {
            return 0;
        }
    }

    private static long clearChildren(File directory, String prefix) {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        long removed = 0;
        for (File file : files) {
            try {
                // Never follow a symlink, including one to another directory within cache: that could cycle.
                File canonical = file.getCanonicalFile();
                if (!canonical.getPath().startsWith(prefix) || !canonical.equals(file.getAbsoluteFile())) continue;
                if (file.isDirectory()) {
                    removed += clearChildren(file, prefix);
                    file.delete();
                } else {
                    long bytes = file.length();
                    if (file.delete()) removed += bytes;
                }
            } catch (IOException | SecurityException ignored) { }
        }
        return removed;
    }
}
