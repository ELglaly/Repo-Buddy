package com.repoinspector.analysis;

import com.intellij.openapi.project.Project;
import com.intellij.psi.util.PsiModificationTracker;
import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple cache for call-chain analysis results, keyed by endpoint identity and
 * invalidated automatically when any PSI modification occurs in the project.
 *
 * <p>Thread-safe: uses a {@link ConcurrentHashMap} and reads the modification
 * count atomically from {@link PsiModificationTracker}.
 */
public final class CallChainCache {

    private record CachedEntry(long modificationCount, List<CallChainNode> nodes) {}

    // Key: "ControllerName#methodSignature"
    private static final Map<String, CachedEntry> CACHE = new ConcurrentHashMap<>();

    private CallChainCache() {
        // utility class
    }

    /**
     * Returns the cached result for {@code endpoint} if it is still valid,
     * or {@code null} if the cache is stale or empty.
     */
    public static List<CallChainNode> getOrNull(EndpointInfo endpoint, Project project) {
        String key = cacheKey(endpoint);
        CachedEntry entry = CACHE.get(key);
        if (entry == null) {
            return null;
        }
        long currentStamp = PsiModificationTracker.getInstance(project).getModificationCount();
        if (entry.modificationCount() != currentStamp) {
            CACHE.remove(key); // stale — evict eagerly
            return null;
        }
        return entry.nodes();
    }

    /**
     * Returns the cached result if valid, otherwise runs a fresh analysis, stores it, and returns it.
     * Safe to call from a background thread.
     *
     * @param endpoint the endpoint to trace
     * @param project  the current project
     * @return analysis nodes — never null
     */
    public static List<CallChainNode> getOrAnalyze(EndpointInfo endpoint, Project project) {
        List<CallChainNode> cached = getOrNull(endpoint, project);
        if (cached != null) {
            return cached;
        }
        List<CallChainNode> nodes = CallChainAnalyzer.analyze(endpoint, project);
        put(endpoint, project, nodes);
        return nodes;
    }

    /**
     * Stores the analysis result for {@code endpoint} associated with the current
     * PSI modification stamp.
     */
    public static void put(EndpointInfo endpoint, Project project, List<CallChainNode> nodes) {
        long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
        CACHE.put(cacheKey(endpoint), new CachedEntry(stamp, List.copyOf(nodes)));
    }

    /** Clears all cached entries (e.g., when switching projects). */
    public static void clear() {
        CACHE.clear();
    }

    private static String cacheKey(EndpointInfo endpoint) {
        return endpoint.controllerName() + "#" + endpoint.methodSignature();
    }
}
