package com.repoinspector.analysis.api;
import com.repoinspector.model.EndpointInfo;

import java.util.List;

/**
 * Discovers Spring MVC REST endpoint methods in the project.
 *
 */
public interface EndpointAnalysisService {
    List<EndpointInfo> findAllEndpoints();

}
