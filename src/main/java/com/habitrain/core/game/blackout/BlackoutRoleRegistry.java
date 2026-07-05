package com.habitrain.core.game.blackout;

import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BlackoutRoleRegistry {
    private static final Map<ResourceLocation, BlackoutRoleDefinition> BY_ID = new LinkedHashMap<>();

    private BlackoutRoleRegistry() {
    }

    public static void register(BlackoutRoleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (BY_ID.containsKey(definition.identifier())) {
            throw new IllegalArgumentException("Role '" + definition.identifier() + "' is already registered");
        }
        BY_ID.put(definition.identifier(), definition);
    }

    public static Optional<BlackoutRoleDefinition> get(ResourceLocation identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(identifier));
    }

    public static Optional<BlackoutRoleDefinition> get(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return get(ResourceLocation.parse(identifier));
    }

    public static Optional<BlackoutRoleDefinition> findBySreRole(SRERole role) {
        if (role == null) {
            return Optional.empty();
        }
        ResourceLocation id = role.getIdentifier();
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<BlackoutRoleDefinition> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    public static List<BlackoutRoleDefinition> getByFaction(Faction faction) {
        if (faction == null) {
            return List.of();
        }
        return BY_ID.values().stream()
                .filter(def -> def.faction() == faction)
                .toList();
    }

    @Nullable
    public static BlackoutRoleDefinition getRandomByFaction(Faction faction, java.util.Random random) {
        List<BlackoutRoleDefinition> candidates = getByFaction(faction).stream()
                .filter(BlackoutRoleDefinition::selectableInRandomAssignment)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    @Nullable
    public static BlackoutRoleDefinition getFirstByFaction(Faction faction) {
        return getByFaction(faction).stream()
                .filter(BlackoutRoleDefinition::selectableInRandomAssignment)
                .findFirst()
                .orElse(null);
    }

    public static boolean isRegistered(ResourceLocation identifier) {
        return BY_ID.containsKey(identifier);
    }

    public static SRERole[] getSreRolesInOrder() {
        return BY_ID.values().stream()
                .map(BlackoutRoleDefinition::sreRole)
                .toArray(SRERole[]::new);
    }
}
