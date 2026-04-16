package com.repoinspector.analysis.api;

import com.repoinspector.model.CallChainNode;
import com.repoinspector.model.EndpointInfo;

import java.util.List;

/**
 * Traces the static call chain from a Spring MVC endpoint to all reachable repository methods.
 *
 * <p>Results are cached and automatically invalidated on any PSI modification.
 * Retrieve via {@code project.getService(CallChainService.class)}.
 */
public interface CallChainService {

    /**
     * Returns the cached call chain for {@code endpoint} if still valid,
     * or runs a fresh DFS analysis and stores the result.
     * Safe to call from a background thread.
     */
    List<CallChainNode> getOrAnalyze(EndpointInfo endpoint);

    /** Clears all cached call-chain results. */
    void clearCache();
}
