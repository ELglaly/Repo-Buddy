package com.repoinspector.model;

import com.intellij.psi.PsiMethod;

/**
 * Represents a Spring REST API endpoint discovered in the project.
 *
 * @param httpMethod      HTTP verb: GET, POST, PUT, DELETE, PATCH, or REQUEST
 * @param path            URL path from the mapping annotation, e.g. "/api/users/{id}"
 * @param controllerName  Simple name of the controller class, e.g. "UserController"
 * @param methodSignature Human-readable signature, e.g. "getUser(Long)"
 * @param psiMethod       PSI reference for analysis and navigation (may be null if unresolvable)
 */
public record EndpointInfo(
        String httpMethod,
        String path,
        String controllerName,
        String methodSignature,
        PsiMethod psiMethod
) {
    @Override
    public String toString() {
        return httpMethod + " " + path + "  [" + controllerName + "." + methodSignature + "]";
    }
}
