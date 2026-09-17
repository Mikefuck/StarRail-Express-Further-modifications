package com.habitrain.core.config;

import com.google.gson.JsonObject;
import java.util.Set;

/** Discards retired Core mode settings while preserving other mods' entries. */
final class RemovedModeConfigMigration {
    private static final Set<String> MODE_IDS = Set.of(
            "habitrain:blackout", "habitrain_core:habitrain:blackout", "sre:blackout");
    private static final Set<String> TASK_IDS = Set.of(
            "habitrain_core:add_coal", "habitrain_core:repair_wiring",
            "habitrain_core:sabotage_wiring", "habitrain_core:furnace_explosion",
            "habitrain_core:maintain_power", "habitrain_core:restore_power",
            "habitrain_core:blackout_search_backpack", "habitrain_core:blackout_betel_quest",
            "habitrain_core:blackout_pet_cat", "habitrain_core:blackout_be_alone",
            "habitrain_core:blackout_look_my_eyes", "habitrain_core:betel_quest");

    private RemovedModeConfigMigration() {}

    static boolean prune(JsonObject root) {
        boolean changed = false;
        JsonObject global = object(root, "global");
        if (global != null) changed |= global.remove("tempPowerPrice") != null;
        JsonObject tasks = object(root, "tasks");
        if (tasks != null) {
            for (String id : TASK_IDS) changed |= tasks.remove(id) != null;
            for (var entry : tasks.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    changed |= entry.getValue().getAsJsonObject().remove("shopPrice") != null;
                }
            }
        }
        changed |= removeModes(object(root, "gameModes"));
        JsonObject vote = object(root, "modeMapVote");
        if (vote != null) changed |= removeModes(object(vote, "modes"));
        return changed;
    }

    private static boolean removeModes(JsonObject modes) {
        if (modes == null) return false;
        boolean changed = false;
        for (String id : MODE_IDS) changed |= modes.remove(id) != null;
        return changed;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }
}
