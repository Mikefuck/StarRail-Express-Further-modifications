package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.SREDisableManager;
import org.agmas.harpymodloader.events.OnGamePlayerRolesConfirm;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Hard cap: at most one of the seven deadly sins may be assigned in a round.
 * Also demotes Lust when lovers is unavailable (map size &lt; 2 or modifier disabled).
 */
public final class SevenSinsMutex {
    private static boolean registered;

    private SevenSinsMutex() {}

    public static void init() {
        if (registered) return;
        registered = true;
        OnGamePlayerRolesConfirm.EVENT.register(SevenSinsMutex::beforeAssign);
        HabiTrainCore.LOGGER.info("[SevenSins] mutex + lust eligibility gate registered");
    }

    static void beforeAssign(ServerLevel level, Map<Player, SRERole> map) {
        if (map == null || map.isEmpty()) return;

        List<Map.Entry<Player, SRERole>> sins = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (SevenSins.isSin(e.getValue())) {
                sins.add(e);
            }
        }
        if (sins.size() > 1) {
            // UUID-stable: keep first, demote the rest.
            sins.sort(Comparator.comparing(a -> a.getKey().getUUID()));
            for (int i = 1; i < sins.size(); i++) {
                Player p = sins.get(i).getKey();
                SRERole removed = sins.get(i).getValue();
                map.put(p, fallbackNonSin(removed));
                HabiTrainCore.LOGGER.info(
                        "[SevenSins] mutex demoted {} from {} (kept first UUID)",
                        p.getGameProfile().getName(),
                        removed != null ? removed.getIdentifier() : "null");
            }
        }

        // Lust requires multi-player map + enabled lovers modifier.
        for (var e : new ArrayList<>(map.entrySet())) {
            SRERole role = e.getValue();
            if (role != null && SevenSins.LUST_ID.equals(role.getIdentifier())) {
                if (!lustEligible(level, map)) {
                    map.put(e.getKey(), fallbackNonSin(role));
                    HabiTrainCore.LOGGER.info(
                            "[SevenSins] lust demoted for {} (lovers unavailable or map size < 2)",
                            e.getKey().getGameProfile().getName());
                }
            }
        }
    }

    static boolean lustEligible(ServerLevel level, Map<Player, SRERole> map) {
        if (map == null || map.size() < 2) return false;
        try {
            var lovers = SEModifiers.LOVERS;
            if (lovers == null) return false;
            return !SREDisableManager.isModifierDisabled(lovers);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn(
                    "[SevenSins] lustEligible lovers API mismatch; fail-open on map size only", t);
            return map.size() >= 2;
        }
    }

    /**
     * Demote a removed sin to a non-sin baseline by role type:
     * 1 civilian, 2 neutrals, 3 n-for-killer, 4 killer, 5 vigilante.
     */
    static SRERole fallbackNonSin(SRERole removed) {
        if (removed == null) {
            return TMMRoles.CIVILIAN;
        }
        int type = removed.getRoleType();
        if (type == 4) {
            return TMMRoles.KILLER != null ? TMMRoles.KILLER : TMMRoles.CIVILIAN;
        }
        // Neutrals / n-for-killer / civilian / vigilante / unknown → civilian
        return TMMRoles.CIVILIAN;
    }
}
