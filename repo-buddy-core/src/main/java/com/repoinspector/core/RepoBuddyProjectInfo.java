package com.repoinspector.core;
import java.util.List;
public record RepoBuddyProjectInfo(String apiVersion, String id, String name, String root,
        String buildSystem, String javaVersion, boolean springBoot, boolean springDataJpa,
        List<String> supportedFrameworks, List<String> enabledRules, String repoBuddyVersion,
        boolean analysisAvailable) {
    public RepoBuddyProjectInfo { supportedFrameworks = List.copyOf(supportedFrameworks); enabledRules = List.copyOf(enabledRules); }
}
