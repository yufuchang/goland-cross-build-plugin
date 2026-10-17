package com.github.yufuchang.crossbuild;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Project-local platform use counts and stable display ordering. */
public final class PlatformRanking {
    private static final List<String> COMMON = List.of(
            "linux/amd64", "linux/arm64", "windows/amd64",
            "darwin/arm64", "windows/arm64", "darwin/amd64");

    private PlatformRanking() { }

    public static List<String> order(Collection<String> available, Map<String, Long> counts) {
        List<String> result = new ArrayList<>(new LinkedHashSet<>(available));
        result.sort(Comparator
                .comparingLong((String platform) -> counts.getOrDefault(platform, 0L)).reversed()
                .thenComparingInt(PlatformRanking::defaultRank)
                .thenComparing(Comparator.naturalOrder()));
        return result;
    }

    private static int defaultRank(String platform) {
        int position = COMMON.indexOf(platform);
        return position < 0 ? COMMON.size() : position;
    }

    public static Map<String, Long> parse(String saved) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (saved == null || saved.isBlank()) return result;
        for (String entry : saved.split(",")) {
            String[] parts = entry.split("=", -1);
            if (parts.length != 2 || !parts[0].matches("[a-z0-9]+/[a-z0-9]+")) continue;
            try {
                long count = Long.parseLong(parts[1]);
                if (count > 0) result.put(parts[0], count);
            } catch (NumberFormatException ignored) {
                // Ignore stale or manually corrupted project preferences.
            }
        }
        return result;
    }

    public static String serialize(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey().matches("[a-z0-9]+/[a-z0-9]+") && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    public static void recordSelection(Map<String, Long> counts, Collection<String> selected) {
        for (String platform : new LinkedHashSet<>(selected)) {
            long current = counts.getOrDefault(platform, 0L);
            counts.put(platform, current == Long.MAX_VALUE ? current : current + 1);
        }
    }
}

