package com.habitrain.core.task;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SlownessReapplyManager {

    public record EffectSpec(int amplifier, int duration, ResourceLocation sourceTag) {}

    private static final Map<ResourceKey<Level>, Map<UUID, EffectSpec>> activeEntries = new ConcurrentHashMap<>();
    private static boolean registered = false;

    public static void registerTickHandler() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeEntries.isEmpty()) return;
            for (var levelEntry : activeEntries.entrySet()) {
                ServerLevel level = server.getLevel(levelEntry.getKey());
                if (level == null) continue;
                Map<UUID, EffectSpec> levelMap = levelEntry.getValue();
                if (levelMap.isEmpty()) continue;
                for (var entry : levelMap.entrySet()) {
                    ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(entry.getKey());
                    if (player == null) continue;
                    EffectSpec spec = entry.getValue();
                    player.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, spec.duration(), spec.amplifier(),
                            false, true, true));
                }
            }
        });
    }

    public static void register(ResourceKey<Level> levelKey, UUID playerId, EffectSpec spec) {
        activeEntries.computeIfAbsent(levelKey, k -> new ConcurrentHashMap<>()).put(playerId, spec);
    }

    public static void unregisterAllLevels(UUID playerId) {
        for (var levelMap : activeEntries.values()) {
            levelMap.remove(playerId);
        }
    }

    public static void clearAll() {
        activeEntries.clear();
    }
}
