package com.repoinspector.analysis;

import com.intellij.openapi.project.Project;
import com.intellij.psi.util.PsiModificationTracker;
import com.repoinspector.model.OutboundApiCall;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-scoped cache for outbound API call analysis results.
 *
 * <p>Results are invalidated automatically whenever any PSI modification occurs,
 * using the same {@link PsiModificationTracker} stamp strategy as {@link CallChainCache}.
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap}.
 */
public final class OutboundCallCache {

    private record CachedEntry(long modificationCount, List<OutboundApiCall> calls) {}

    private static final Map<String, CachedEntry> CACHE = new ConcurrentHashMap<>();

    private OutboundCallCache() {}

    /**
     * Returns the cached result for {@code project} if still valid, or {@code null} if stale.
     */
    public static List<OutboundApiCall> getOrNull(Project project) {
        String key = cacheKey(project);
        CachedEntry entry = CACHE.get(key);
        if (entry == null) return null;
        long current = PsiModificationTracker.getInstance(project).getModificationCount();
        if (entry.modificationCount() != current) {
            CACHE.remove(key);
            return null;
        }
        return entry.calls();
    }

    /**
     * Stores the analysis result for {@code project} keyed to the current PSI stamp.
     */
    public static void put(Project project, List<OutboundApiCall> calls) {
        long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
        CACHE.put(cacheKey(project), new CachedEntry(stamp, List.copyOf(calls)));
    }

    /** Clears all cached entries. */
    public static void clear() {
        CACHE.clear();
    }

    private static String cacheKey(Project project) {
        return "outbound:" + project.getName();
    }
}
