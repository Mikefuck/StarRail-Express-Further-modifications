package com.habitrain.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Server-side authorization and JSON filtering for configuration updates. */
public final class ConfigUpdateAccessPolicy {
    private ConfigUpdateAccessPolicy() {}

    public static boolean isAllowed(ConfigUpdateScope scope, boolean hasOp2,
                                    boolean dedicatedServer, boolean menuGateEnabled,
                                    boolean menuGateAllowed) {
        if (!hasOp2) return false;
        if (scope != ConfigUpdateScope.FULL_MOD_MENU) return true;
        return !dedicatedServer || !menuGateEnabled || menuGateAllowed;
    }

    /**
     * Drops every section that the selected backpack entry point is not allowed to change.
     * The scope comes from the client, so it narrows the request but never grants privilege.
     */
    public static String filterConfigJson(ConfigUpdateScope scope, String configJson) {
        JsonElement parsed = JsonParser.parseString(configJson == null ? "{}" : configJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Configuration update root must be an object");
        }
        JsonObject source = parsed.getAsJsonObject();
        source.remove("_habitrain_config_update_scope");
        if (scope == ConfigUpdateScope.FULL_MOD_MENU) return source.toString();

        JsonObject filtered = new JsonObject();
        String permittedSection = scope == ConfigUpdateScope.BACKPACK_TASKS
                ? "tasks" : "modeMapVote";
        JsonElement section = source.get(permittedSection);
        if (section != null && section.isJsonObject()) {
            filtered.add(permittedSection, section.deepCopy());
        }
        return filtered.toString();
    }
}
