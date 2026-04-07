package com.repoinspector.model;

/**
 * Immutable data record representing a single Spring repository method
 * and how many times it is called within the project.
 */
public record RepositoryMethodInfo(
        String repositoryName,
        String methodName,
        String methodSignature,
        int callCount
) {}
