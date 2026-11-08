package com.habitrain.core.config;

/** Confined to the client thread, including integrated-server hosts. */
final class ClientConfigSession {
    volatile ConfigRepository mirror;
    final ConfigRepository draft;
    volatile String baseJson;
    volatile long baseRevision;
    volatile long mirrorRevision;
    volatile boolean conflicted;
    volatile boolean remote;
    volatile boolean editing;
    volatile boolean dirty;
    ClientConfigSession(ConfigRepository initial, ConfigStore store) {
        mirror = initial.copy(); draft = initial.copy(); baseJson = store.toJsonString(draft);
    }
    synchronized void receive(ConfigRepository latest, String json, long revision) {
        mirror = latest; mirrorRevision = revision; remote = true;
        if (!editing && !dirty) { draft.copyFrom(latest); baseJson = json; baseRevision = revision; }
    }
}
