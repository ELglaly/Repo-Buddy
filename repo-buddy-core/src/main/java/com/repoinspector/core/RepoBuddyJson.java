package com.repoinspector.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;

public final class RepoBuddyJson {
    private static final Gson COMPACT = builder().create();
    private static final Gson PRETTY = builder().setPrettyPrinting().create();
    private RepoBuddyJson() {}
    public static Gson gson(boolean pretty) { return pretty ? PRETTY : COMPACT; }
    private static GsonBuilder builder() {
        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                        (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>)
                        (value, type, context) -> Instant.parse(value.getAsString()));
    }
}
