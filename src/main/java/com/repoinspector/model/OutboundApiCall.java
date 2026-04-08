package com.repoinspector.model;

import com.intellij.psi.PsiMethod;

import java.util.List;
import java.util.Map;

/**
 * Represents a single outbound HTTP API call detected via PSI analysis.
 *
 * @param callerClass   Simple name of the class that makes the call.
 * @param callerMethod  Human-readable method signature of the caller, e.g. "fetchUser(Long)".
 * @param httpMethod    HTTP verb detected: GET, POST, PUT, DELETE, PATCH, or UNKNOWN.
 * @param resolvedUrl   Best-effort resolved URL; may still contain unresolved placeholders
 *                      like {@code {id}} or the literal token {@code [UNRESOLVED]}.
 * @param headers       Explicitly set request headers. Empty map if none detected.
 * @param queryParams   Explicitly set query parameters. Empty map if none detected.
 * @param bodyFields    Detected body field names. {@code null} when there is definitively no body.
 * @param confidence    How completely the URL and call were resolved.
 * @param reason        Human-readable explanation of what was detected and how it was resolved.
 * @param clientType    Which HTTP client library was detected.
 * @param psiMethod     PSI reference to the calling method for IDE navigation (may be null).
 */
public record OutboundApiCall(
        String callerClass,
        String callerMethod,
        String httpMethod,
        String resolvedUrl,
        Map<String, String> headers,
        Map<String, String> queryParams,
        List<String> bodyFields,
        ConfidenceLevel confidence,
        String reason,
        ClientType clientType,
        PsiMethod psiMethod
) {
    public enum ConfidenceLevel {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum ClientType {
        REST_TEMPLATE,
        WEB_CLIENT,
        OK_HTTP,
        FEIGN,
        HTTP_URL_CONNECTION,
        APACHE_HTTP_CLIENT,
        UNKNOWN
    }
}
