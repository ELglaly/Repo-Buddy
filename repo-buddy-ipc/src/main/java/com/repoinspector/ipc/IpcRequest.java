package com.repoinspector.ipc;

import com.google.gson.JsonObject;

public record IpcRequest(String protocolVersion, String action, String projectId, JsonObject payload) {
    public static final String VERSION = "1";
}
