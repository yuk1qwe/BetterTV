package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WeberinCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void clearsNestedCacheAndReportsActuallyRemovedBytes() throws Exception {
        File cache = temporary.newFolder("cache");
        File nested = new File(cache, "images");
        assertTrue(nested.mkdir());
        Files.write(new File(cache, "one.tmp").toPath(), new byte[7]);
        Files.write(new File(nested, "two.tmp").toPath(), new byte[11]);
        assertEquals(18, WeberinCache.clear(cache));
        assertTrue(cache.isDirectory());
        assertEquals(0, cache.listFiles().length);
        assertEquals(0, WeberinCache.clear(cache));
    }

    @Test public void preservesSettingsAndFavoritesOutsideCache() throws Exception {
        File cache = temporary.newFolder("cache");
        File preferences = temporary.newFolder("shared_prefs");
        File favorites = new File(preferences, "settings.xml");
        Files.write(favorites.toPath(), new byte[12]);
        File image = new File(cache, "banner.tmp");
        Files.write(image.toPath(), new byte[5]);
        assertEquals(5, WeberinCache.clear(cache));
        assertFalse(image.exists());
        assertTrue(favorites.exists());
        assertEquals(12, favorites.length());
    }

    @Test public void doesNotFollowExternalLinksOrCacheCycles() throws Exception {
        File cache = temporary.newFolder("cache");
        File outside = temporary.newFolder("outside");
        File protectedFile = new File(outside, "saved.txt");
        Files.write(protectedFile.toPath(), new byte[13]);
        Files.createSymbolicLink(new File(cache, "outside").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(cache, "cycle").toPath(), cache.toPath());
        Files.write(new File(cache, "safe.tmp").toPath(), new byte[3]);
        assertEquals(3, WeberinCache.clear(cache));
        assertTrue(protectedFile.exists());
        assertEquals(13, protectedFile.length());
    }
}
