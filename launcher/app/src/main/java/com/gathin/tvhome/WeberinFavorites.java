package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An isolated editing session. Opening or cancelling it never writes the owner's preferences. */
final class WeberinFavorites {
    private final Set<String> installed = new HashSet<>();
    private final List<String> selected = new ArrayList<>();

    WeberinFavorites(Collection<String> installedPackages, Collection<String> selectedPackages) {
        for (String pkg : installedPackages) if (pkg != null && !pkg.isEmpty()) installed.add(pkg);
        for (String pkg : selectedPackages)
            if (installed.contains(pkg) && !selected.contains(pkg)) selected.add(pkg);
    }

    boolean contains(String pkg) { return selected.contains(pkg); }
    int size() { return selected.size(); }
    int indexOf(String pkg) { return selected.indexOf(pkg); }
    List<String> packages() { return Collections.unmodifiableList(new ArrayList<>(selected)); }

    boolean toggle(String pkg) {
        if (!installed.contains(pkg)) return false;
        if (!selected.remove(pkg)) selected.add(pkg);
        return true;
    }

    boolean move(String pkg, int direction) {
        if (direction != -1 && direction != 1) return false;
        int from = selected.indexOf(pkg), to = from + direction;
        if (from < 0 || to < 0 || to >= selected.size()) return false;
        Collections.swap(selected, from, to);
        return true;
    }
}
