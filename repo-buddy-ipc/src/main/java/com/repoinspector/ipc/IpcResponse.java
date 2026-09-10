package com.repoinspector.ipc;

import com.google.gson.JsonElement;
import com.repoinspector.core.RepoBuddyError;

public record IpcResponse(String protocolVersion, JsonElement result, RepoBuddyError error) {
    public static IpcResponse success(JsonElement result) { return new IpcResponse(IpcRequest.VERSION, result, null); }
    public static IpcResponse failure(RepoBuddyError error) { return new IpcResponse(IpcRequest.VERSION, null, error); }
}
