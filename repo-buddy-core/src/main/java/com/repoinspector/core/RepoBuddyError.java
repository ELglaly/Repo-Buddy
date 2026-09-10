package com.repoinspector.core;
import java.util.Map;
public record RepoBuddyError(String apiVersion, ErrorBody error) {
    public record ErrorBody(RepoBuddyErrorCode code, String message, Map<String, String> details) {}
    public static RepoBuddyError of(RepoBuddyException e) { return new RepoBuddyError("1", new ErrorBody(e.code(), e.getMessage(), Map.of())); }
}
