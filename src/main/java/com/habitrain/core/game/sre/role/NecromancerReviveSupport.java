package com.habitrain.core.game.sre.role;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Mike 将杀手转为非杀手时，若场上有存活死灵，给世界 NecromancerComponent +1 可用复活。
 */
public final class NecromancerReviveSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("NecromancerReviveSupport");

    /** stupid_express 死灵 */
    private static final ResourceLocation NECROMANCER_ID =
            ResourceLocation.fromNamespaceAndPath("stupid_express", "necromancer");
    /** noelles 猫死灵（SRE.wifiId 命名空间以运行时 id 为准；常见 wifi / starrailexpress） */
    private static final ResourceLocation CAT_NECROMANCER_WIFI =
            ResourceLocation.fromNamespaceAndPath("wifi", "cat_necromancer");
    private static final ResourceLocation CAT_NECROMANCER_SRE =
            ResourceLocation.fromNamespaceAndPath("starrailexpress", "cat_necromancer");

    private static final Set<ResourceLocation> NECRO_IDS = Set.of(
            NECROMANCER_ID, CAT_NECROMANCER_WIFI, CAT_NECROMANCER_SRE
    );

    private static volatile boolean componentUnavailableLogged;

    private NecromancerReviveSupport() {}

    /**
     * @return true if availableRevives was increased
     */
    public static boolean onKillerConvertedAway(ServerLevel level, SRERole oldRole, SRERole nextRole) {
        if (level == null || oldRole == null || nextRole == null) return false;
        if (!oldRole.canUseKiller()) return false;
        if (nextRole.canUseKiller()) return false;
        if (!hasLivingNecromancer(level)) return false;
        return increaseRevives(level);
    }

    static boolean hasLivingNecromancer(ServerLevel level) {
        SREGameWorldComponent game;
        try {
            game = SREGameWorldComponent.KEY.get(level);
        } catch (Throwable t) {
            return false;
        }
        if (game == null) return false;

        Set<ResourceLocation> extra = resolveNecroIdsFromClasspath();
        for (Player p : level.players()) {
            if (!(p instanceof ServerPlayer sp)) continue;
            if (!GameUtils.isPlayerAliveAndSurvival(sp)) continue;
            SRERole role;
            try {
                role = game.getRole(sp);
            } catch (Throwable t) {
                continue;
            }
            if (role == null || role.identifier() == null) continue;
            ResourceLocation id = role.identifier();
            if (NECRO_IDS.contains(id) || extra.contains(id)) {
                return true;
            }
            // path fallback if namespace drifts
            String path = id.getPath();
            if ("necromancer".equals(path) || "cat_necromancer".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceLocation> resolveNecroIdsFromClasspath() {
        Set<ResourceLocation> out = new HashSet<>();
        addRoleIdField(out, "pro.fazeclan.river.stupid_express.constants.SERoles", "NECROMANCER");
        addRoleIdField(out, "org.agmas.noellesroles.role.BounsRoles", "CAT_NECROMANCER");
        return out;
    }

    private static void addRoleIdField(Set<ResourceLocation> out, String className, String field) {
        try {
            Class<?> c = Class.forName(className);
            Object role = c.getField(field).get(null);
            if (role instanceof SRERole sre && sre.identifier() != null) {
                out.add(sre.identifier());
            }
        } catch (Throwable ignored) {
            // optional DLC classes
        }
    }

    private static boolean increaseRevives(ServerLevel level) {
        try {
            Class<?> compClass = Class.forName(
                    "pro.fazeclan.river.stupid_express.role.necromancer.cca.NecromancerComponent");
            Object key = compClass.getField("KEY").get(null);
            Method get = key.getClass().getMethod("get", Object.class);
            Object component = get.invoke(key, level);
            if (component == null) return false;
            Method increase = compClass.getMethod("increaseAvailableRevives");
            increase.invoke(component);
            try {
                Method sync = compClass.getMethod("sync");
                sync.invoke(component);
            } catch (NoSuchMethodException ignored) {
                // older shape
            }
            int available = -1;
            try {
                Method getter = compClass.getMethod("getAvailableRevives");
                Object v = getter.invoke(component);
                if (v instanceof Integer i) available = i;
            } catch (Throwable ignored) {}
            LOGGER.info("[Necro] +1 revive after killer->non-killer; availableRevives={}", available);
            return true;
        } catch (Throwable t) {
            if (!componentUnavailableLogged) {
                componentUnavailableLogged = true;
                LOGGER.warn("[Necro] NecromancerComponent unavailable; skip revive credit", t);
            }
            return false;
        }
    }
}
