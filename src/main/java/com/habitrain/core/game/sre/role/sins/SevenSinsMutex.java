package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.Harpymodloader;
import org.agmas.harpymodloader.SREDisableManager;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.harpymodloader.events.OnGamePlayerRolesConfirm;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One deadly sin per round + natural spawn gates.
 * Forced roles ({@code FORCED_MODDED_ROLE_FLIP}) are never demoted by spawn gates.
 */
public final class SevenSinsMutex {
    private static boolean registered;

    /** Greed only appears naturally when participant count is greater than this. */
    public static final int GREED_MIN_PLAYERS_EXCLUSIVE = 12;

    private SevenSinsMutex() {}

    public static void init() {
        if (registered) return;
        registered = true;
        OnGamePlayerRolesConfirm.EVENT.register(SevenSinsMutex::beforeAssign);
        HabiTrainCore.LOGGER.info(
                "[SevenSins] mutex + lust/envy/greed natural gates registered (forced protected)");
    }

    static void beforeAssign(ServerLevel level, Map<Player, SRERole> map) {
        if (map == null || map.isEmpty()) return;
        Map<UUID, SRERole> forced = readForcedRoles();

        // Natural Lust only when lovers can exist; forced Lust is kept for testing.
        for (var entry : new ArrayList<>(map.entrySet())) {
            SRERole role = entry.getValue();
            if (role == null || !SevenSins.LUST_ID.equals(role.getIdentifier())) continue;
            if (isForcedSin(entry, forced)) continue;
            if (!lustEligible(level, map)) {
                map.put(entry.getKey(), fallbackNonSin(role));
                HabiTrainCore.LOGGER.info("[SevenSins] lust removed (natural): no valid lover pair");
            }
        }

        // Natural Greed only when players > 12.
        int participants = map.size();
        for (var entry : new ArrayList<>(map.entrySet())) {
            SRERole role = entry.getValue();
            if (role == null || !SevenSins.GREED_ID.equals(role.getIdentifier())) continue;
            if (isForcedSin(entry, forced)) continue;
            if (participants <= GREED_MIN_PLAYERS_EXCLUSIVE) {
                map.put(entry.getKey(), fallbackNonSin(role));
                HabiTrainCore.LOGGER.info(
                        "[SevenSins] greed removed (natural): participants {} <= {}",
                        participants, GREED_MIN_PLAYERS_EXCLUSIVE);
            }
        }

        List<Map.Entry<Player, SRERole>> sins = new ArrayList<>();
        for (var entry : map.entrySet()) {
            if (SevenSins.isSin(entry.getValue())) sins.add(entry);
        }
        if (sins.size() > 1) {
            // Forced sins first, then stable UUID order.
            sins.sort(Comparator
                    .comparing((Map.Entry<Player, SRERole> e) -> !isForcedSin(e, forced))
                    .thenComparing(e -> e.getKey().getUUID()));
            for (int i = 1; i < sins.size(); i++) {
                Player player = sins.get(i).getKey();
                SRERole removed = sins.get(i).getValue();
                // If this trailing entry is also forced, still demote (only one sin slot).
                map.put(player, fallbackNonSin(removed));
                HabiTrainCore.LOGGER.info("[SevenSins] mutex replaced {} from {} (forcedKeep={})",
                        player.getGameProfile().getName(),
                        removed.getIdentifier(),
                        isForcedSin(sins.get(0), forced));
            }
        }

        // Natural Envy only when killer-capable roles ≥ 2; forced Envy kept.
        int killerCapable = 0;
        for (SRERole role : map.values()) {
            if (role != null && role.canUseKiller()) {
                killerCapable++;
            }
        }
        if (killerCapable < 2) {
            for (var entry : new ArrayList<>(map.entrySet())) {
                SRERole role = entry.getValue();
                if (role == null || !SevenSins.ENVY_ID.equals(role.getIdentifier())) continue;
                if (isForcedSin(entry, forced)) {
                    HabiTrainCore.LOGGER.info(
                            "[SevenSins] envy forced keep for {} despite killer count {}",
                            entry.getKey().getGameProfile().getName(), killerCapable);
                    continue;
                }
                map.put(entry.getKey(), fallbackNonSin(role));
                HabiTrainCore.LOGGER.info(
                        "[SevenSins] envy removed (natural) for {}: killer-capable count {}",
                        entry.getKey().getGameProfile().getName(), killerCapable);
            }
        }
    }

    /** True if this player is forced to this exact sin role. */
    public static boolean isForcedSinPlayer(Player player, SRERole role) {
        if (player == null || role == null || !SevenSins.isSin(role)) return false;
        Map<UUID, SRERole> forced = readForcedRoles();
        SRERole f = forced.get(player.getUUID());
        return f != null && role.getIdentifier().equals(f.getIdentifier());
    }

    private static boolean isForcedSin(Map.Entry<Player, SRERole> entry, Map<UUID, SRERole> forced) {
        if (entry == null || entry.getKey() == null || entry.getValue() == null) return false;
        SRERole forcedRole = forced.get(entry.getKey().getUUID());
        if (forcedRole == null) return false;
        return SevenSins.isSin(forcedRole)
                && forcedRole.getIdentifier().equals(entry.getValue().getIdentifier());
    }

    private static Map<UUID, SRERole> readForcedRoles() {
        try {
            Map<UUID, SRERole> flip = Harpymodloader.FORCED_MODDED_ROLE_FLIP;
            if (flip == null || flip.isEmpty()) return Map.of();
            return new HashMap<>(flip);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[SevenSins] FORCED_MODDED_ROLE_FLIP unavailable", t);
            return Map.of();
        }
    }

    static boolean lustEligible(ServerLevel level, Map<Player, SRERole> map) {
        if (map == null || map.size() < 3) return false;
        try {
            var lovers = SEModifiers.LOVERS;
            if (lovers == null || SREDisableManager.isModifierDisabled(lovers)) return false;

            WorldModifierComponent modifiers = WorldModifierComponent.getInstance(level);
            if (modifiers != null && !modifiers.getModifiers().isEmpty()) {
                long loversAssigned = map.keySet().stream()
                        .filter(player -> modifiers.isModifier(player, lovers))
                        .count();
                return loversAssigned >= 2;
            }
            return true;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SevenSins] lover eligibility API mismatch; disabling Lust", t);
            return false;
        }
    }

    /**
     * Same-slot-type fallback that never returns {@link TMMRoles#LOOSE_END}
     * (亡命徒) for neutral sins — that was turning forced Lust into 亡命徒.
     */
    public static SRERole fallbackNonSin(SRERole removed) {
        if (removed == null) return TMMRoles.CIVILIAN;
        int type = removed.getRoleType();
        if (type == TMMRoles.KILLER.getRoleType()) return TMMRoles.KILLER;
        if (type == TMMRoles.VIGILANTE.getRoleType()) return TMMRoles.VIGILANTE;
        if (type == TMMRoles.CIVILIAN.getRoleType()) return TMMRoles.CIVILIAN;

        // Neutrals / other: prefer a non-sin same-type role that is not loose-end / other-mode.
        SRERole candidate = TMMRoles.ROLES.values().stream()
                .filter(role -> role != null && !SevenSins.isSin(role))
                .filter(role -> role.getRoleType() == type)
                .filter(role -> role != TMMRoles.LOOSE_END)
                .filter(role -> {
                    try {
                        return !role.isOtherModeRole();
                    } catch (Throwable t) {
                        return true;
                    }
                })
                .filter(role -> !SREDisableManager.isRoleDisabled(role))
                .filter(role -> {
                    try {
                        return role.canBeRandomedDefination();
                    } catch (Throwable t) {
                        return true;
                    }
                })
                .sorted(Comparator.comparing(role -> role.getIdentifier().toString()))
                .findFirst()
                .orElse(null);
        if (candidate != null) return candidate;

        // Last resort for neutrals: civilian, never loose_end.
        return TMMRoles.CIVILIAN;
    }
}
