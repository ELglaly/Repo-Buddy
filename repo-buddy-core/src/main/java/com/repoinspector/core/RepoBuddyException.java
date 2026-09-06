package com.repoinspector.core;
public final class RepoBuddyException extends RuntimeException {
    private final RepoBuddyErrorCode code;
    public RepoBuddyException(RepoBuddyErrorCode code, String message) { super(message); this.code = code; }
    public RepoBuddyException(RepoBuddyErrorCode code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public RepoBuddyErrorCode code() { return code; }
}
