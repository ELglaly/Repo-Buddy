package com.repoinspector.core;

/** Safe project identity exposed to local clients; never contains session tokens. */
public record RepoBuddyProjectRef(String id, String name, String root, boolean available) {}
