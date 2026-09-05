package com.habitrain.core.scene.model;

import java.util.Collection;

/** Resolves the connection-local background that should remain selected when reopening the editor. */
public final class SceneEditorBackgroundPolicy {
    private SceneEditorBackgroundPolicy() {}

    public static String resolve(String serverBackgroundId, String rememberedBackgroundId,
                                 Collection<String> availableBackgroundIds) {
        String server = SceneBackgroundKey.normalizeBackgroundId(serverBackgroundId);
        String remembered = SceneBackgroundKey.normalizeBackgroundId(rememberedBackgroundId);
        boolean serverAvailable = contains(availableBackgroundIds, server);
        boolean rememberedAvailable = contains(availableBackgroundIds, remembered);

        // A custom server selection is authoritative. When the server still reports the
        // default (for example while the preceding selection packet is being applied), keep
        // the valid selection made by this client in the current play connection.
        if (!SceneBackgroundKey.isDefault(server) && serverAvailable) return server;
        if (rememberedAvailable) return remembered;
        if (serverAvailable) return server;
        return SceneBackgroundKey.DEFAULT_ID;
    }

    private static boolean contains(Collection<String> values, String target) {
        if (values == null || values.isEmpty()) return false;
        for (String value : values) {
            if (SceneBackgroundKey.normalizeBackgroundId(value).equals(target)) return true;
        }
        return false;
    }
}
