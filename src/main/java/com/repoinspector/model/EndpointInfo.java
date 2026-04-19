package com.repoinspector.model;

import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a Spring REST API endpoint discovered in the project.
 *
 * @param httpMethod               HTTP verb: GET, POST, PUT, DELETE, PATCH, or REQUEST
 * @param path                     URL path from the mapping annotation, e.g. "/api/users/{id}"
 * @param controllerName           Simple name of the controller class, e.g. "UserController"
 * @param methodSignature          Human-readable signature, e.g. "getUser(Long)"
 * @param qualifiedControllerName  FQN of the controller class; used to re-resolve PSI for navigation
 * @param psiMethod                Live PSI reference for DFS traversal; null in cached/stored copies
 */
public record EndpointInfo(
        String httpMethod,
        String path,
        String controllerName,
        String methodSignature,
        @Nullable String qualifiedControllerName,
        @Nullable PsiMethod psiMethod
) {
    /** Returns a copy of this endpoint with the PSI reference stripped for safe long-term storage. */
    public EndpointInfo withoutPsi() {
        return new EndpointInfo(httpMethod, path, controllerName, methodSignature,
                qualifiedControllerName, null);
    }

    @Override
    public String toString() {
        return httpMethod + " " + path + "  [" + controllerName + "." + methodSignature + "]";
    }
}
