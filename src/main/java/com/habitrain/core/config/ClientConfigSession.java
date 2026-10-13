package com.habitrain.core.config;

/** Confined to the client thread, including integrated-server hosts. */
final class ClientConfigSession {
    ConfigRepository mirror;
    final ConfigRepository draft;
    String baseJson;
    long baseRevision;
    long mirrorRevision;
    boolean conflicted;
    boolean remote;
    boolean editing;
    boolean dirty;
    ClientConfigSession(ConfigRepository initial, ConfigStore store) {
        mirror = initial.copy(); draft = initial.copy(); baseJson = store.toJsonString(draft);
    }
    void receive(ConfigRepository latest, String json, long revision) {
        mirror = latest; mirrorRevision = revision; remote = true;
        if (!editing && !dirty) { draft.copyFrom(latest); baseJson = json; baseRevision = revision; }
    }
}
