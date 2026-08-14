package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleChangeApi;
import com.habitrain.core.api.role.v2.RoleChangeCause;
import com.habitrain.core.api.role.v2.RoleChangeResult;
import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.RoleCombatHooks;
import com.habitrain.core.api.role.v2.behavior.RoleHookContext;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleLifecycleHooks;
import com.habitrain.core.game.sre.role.component.CrimeScapegoatComponent;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.agmas.noellesroles.utils.RoleUtils;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v2 managed hooks for habitrain_core's own roles. Registered through
 * {@link RoleExtensionApi} so the central dispatcher owns the global
 * listeners; {@link HabiRoleEvents} only keeps world-level leftovers
 * (pepper spray, hidden-body tick) that are not role-scoped.
 */
public final class HabiRoleHooks {

    private HabiRoleHooks() {}

    private static boolean registered;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        var registrar = RoleExtensionApi.instance().registrar();
        registrar.hooks(RoleKey.of(HabiRoles.CRIME_SCAPEGOAT_ID), crimeScapegoat());
        registrar.hooks(RoleKey.of(HabiRoles.FLOWER_GIRL_ID), flowerGirl());
        registrar.hooks(RoleKey.of(HabiRoles.SWIFT_WIND_ID), swiftWind());
        registrar.hooks(RoleKey.of(HabiRoles.MIME_KILLER_ID), mimeKiller());
    }

    private static RoleHooks crimeScapegoat() {
        return RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        if (player != null) {
                            CrimeScapegoatComponent.KEY.get(player).init();
                        }
                    }
                })
                .combat(new RoleCombatHooks() {
                    @Override
                    public Decision allowDeathByKiller(ServerPlayer victim, ServerPlayer killer,
                                                       ResourceLocation deathReason, RoleHookContext ctx) {
                        if (victim == null) {
                            return Decision.PASS;
                        }
                        CrimeScapegoatComponent c = CrimeScapegoatComponent.KEY.get(victim);
                        if (c == null || !c.isInKnifeWindow() || c.isConverted()) {
                            return Decision.PASS;
                        }
                        if (deathReason != null) {
                            String path = deathReason.getPath();
                            if ("fell_out_of_train".equals(path) || "disconnected".equals(path)) {
                                return Decision.PASS;
                            }
                        }
                        if (!convertScapegoatToRandomKiller(victim, c)) {
                            return Decision.PASS;
                        }
                        victim.setHealth(victim.getMaxHealth());
                        victim.displayClientMessage(
                                Component.literal("§c[凶案替罪羊] 你在刀下倒下，却以杀手之身站起……"),
                                true);
                        return Decision.DENY;
                    }

                    @Override
                    public void onAnyDeath(ServerPlayer dead, ResourceLocation deathReason, RoleHookContext ctx) {
                        if (dead == null || !(dead.level() instanceof ServerLevel level)) {
                            return;
                        }
                        triggerNearbyScapegoats(level, dead);
                    }
                })
                .build();
    }

    private static RoleHooks flowerGirl() {
        return RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        if (player != null) {
                            FlowerGirlComponent.KEY.get(player).init();
                        }
                    }
                })
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, ResourceLocation deathReason, RoleHookContext ctx) {
                        if (player != null && player.level() instanceof ServerLevel level) {
                            FlowerGirlComponent.clearAllBouquets(level);
                        }
                    }
                })
                .build();
    }

    private static RoleHooks swiftWind() {
        return RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        if (player == null) {
                            return;
                        }
                        SwiftWindComponent.KEY.get(player).init();
                        try {
                            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(player);
                            if (shop != null) {
                                shop.setBalance(SwiftWindComponent.STARTING_BALANCE);
                            }
                        } catch (Throwable t) {
                            HabiTrainCore.LOGGER.warn("[HabiRoleHooks] failed to set swift wind balance", t);
                        }
                    }
                })
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onKill(ServerPlayer victim, ServerPlayer killer,
                                       ResourceLocation deathReason, RoleHookContext ctx) {
                        handleSwiftWindKill(killer, deathReason);
                    }
                })
                .build();
    }

    private static RoleHooks mimeKiller() {
        return RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        if (player != null) {
                            MimeKillerComponent.KEY.get(player).init();
                        }
                    }
                })
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeathWithBody(ServerPlayer victim, ServerPlayer killer,
                                                ResourceLocation deathReason, PlayerBodyEntity body,
                                                RoleHookContext ctx) {
                        if (killer == null || body == null) {
                            return;
                        }
                        if (!HabiRoles.isHabiRole(killer, HabiRoles.MIME_KILLER)) {
                            return;
                        }
                        MimeKillerComponent.hideBody(body, MimeKillerComponent.BODY_HIDE_SECONDS * 20);
                    }
                })
                .build();
    }

    static void triggerNearbyScapegoats(ServerLevel level, ServerPlayer dead) {
        if (level == null || dead == null) {
            return;
        }
        double rangeSq = CrimeScapegoatComponent.NEARBY_DEATH_RANGE * CrimeScapegoatComponent.NEARBY_DEATH_RANGE;
        for (ServerPlayer p : level.players()) {
            if (p == dead || p.isSpectator()) {
                continue;
            }
            if (!HabiRoles.isHabiRole(p, HabiRoles.CRIME_SCAPEGOAT)) {
                continue;
            }
            if (p.distanceToSqr(dead) > rangeSq) {
                continue;
            }
            try {
                CrimeScapegoatComponent c = CrimeScapegoatComponent.KEY.get(p);
                if (c != null) {
                    c.tryTriggerFromNearbyDeath(p);
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Scapegoat] trigger failed for {}", p.getName().getString(), t);
            }
        }
    }

    static void handleSwiftWindKill(@Nullable ServerPlayer killer, @Nullable ResourceLocation deathReason) {
        if (killer == null) {
            return;
        }
        SwiftWindComponent c = SwiftWindComponent.KEY.get(killer);
        c.onAnyKill();
        boolean throwing = false;
        if (deathReason != null) {
            String full = deathReason.toString();
            String path = deathReason.getPath();
            throwing = full.contains("throwing_knife")
                    || path.contains("throwing_knife")
                    || path.contains("throwing");
        }
        ItemStack main = killer.getMainHandItem();
        if (!main.isEmpty()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(main.getItem());
            if (key != null && key.getPath().contains("throwing_knife")) {
                throwing = true;
            }
        }
        if (throwing) {
            c.onThrowingKnifeKill(killer);
        }
    }

    static boolean convertScapegoatToRandomKiller(ServerPlayer victim, CrimeScapegoatComponent c) {
        List<SRERole> pool = CrimeScapegoatComponent.randomKillerPool();
        if (pool.isEmpty()) {
            HabiTrainCore.LOGGER.warn("[Scapegoat] no killer pool for conversion");
            return false;
        }
        SRERole next = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        try {
            RoleChangeResult result = RoleChangeApi.instance()
                    .transform(victim, RoleKey.of(next.identifier()), RoleChangeCause.CONVERSION);
            if (!result.success()) {
                HabiTrainCore.LOGGER.warn("[Scapegoat] RoleChangeApi transform failed: {}", result.message());
                return false;
            }
            c.markConverted();
            try {
                RoleUtils.sendWelcomeAnnouncement(victim);
            } catch (Throwable ignored) {}
            try {
                SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(victim);
                if (shop != null && shop.balance < 100) {
                    shop.setBalance(100);
                }
            } catch (Throwable ignored) {}
            HabiTrainCore.LOGGER.info("[Scapegoat] {} converted to {} after death in knife window",
                    victim.getName().getString(), next.identifier());
            return true;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[Scapegoat] conversion failed", t);
            return false;
        }
    }
}
