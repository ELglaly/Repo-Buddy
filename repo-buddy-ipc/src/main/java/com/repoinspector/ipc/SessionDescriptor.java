package com.repoinspector.ipc;

public record SessionDescriptor(String protocolVersion, int port, long pid, String projectId,
        String projectName, String projectRoot, String token) {}
