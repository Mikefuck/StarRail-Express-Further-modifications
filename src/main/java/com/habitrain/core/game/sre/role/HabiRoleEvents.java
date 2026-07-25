package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.component.CrimeScapegoatComponent;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnDeathWithBody;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 职业事件：分配 / 死亡 / 击杀 / 防狼喷雾 / 服务器 tick。
 */
public final class HabiRoleEvents {
    private HabiRoleEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        ModdedRoleAssigned.EVENT.register((player, role) -> {
            if (player == null || role == null) return;
            if (!(player instanceof ServerPlayer sp)) return;
            ResourceLocation id = role.identifier();
            if (HabiRoles.CRIME_SCAPEGOAT_ID.equals(id)) {
                CrimeScapegoatComponent.KEY.get(sp).init();
            } else if (HabiRoles.FLOWER_GIRL_ID.equals(id)) {
                FlowerGirlComponent.KEY.get(sp).init();
            } else if (HabiRoles.SWIFT_WIND_ID.equals(id)) {
                SwiftWindComponent.KEY.get(sp).init();
                try {
                    SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(sp);
                    if (shop != null) {
                        shop.setBalance(SwiftWindComponent.STARTING_BALANCE);
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[HabiRoleEvents] failed to set swift wind balance", t);
                }
            } else if (HabiRoles.MIME_KILLER_ID.equals(id)) {
                MimeKillerComponent.KEY.get(sp).init();
            }
        });

        // 刀窗口内被击杀：取消死亡并转随机杀手（对齐赌徒 AllowPlayerDeathWithKiller）
        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return true;
            if (!HabiRoles.isHabiRole(dead, HabiRoles.CRIME_SCAPEGOAT)) return true;
            CrimeScapegoatComponent c = CrimeScapegoatComponent.KEY.get(dead);
            if (c == null || !c.isInKnifeWindow() || c.isConverted()) return true;

            if (deathReason != null) {
                String path = deathReason.getPath();
                if ("fell_out_of_train".equals(path) || "disconnected".equals(path)) {
                    return true;
                }
            }

            convertScapegoatToRandomKiller(dead, c);
            // 取消死亡后补满血（赌徒会传送回房间；此处原地续命）
            dead.setHealth(dead.getMaxHealth());
            dead.displayClientMessage(
                    Component.literal("§c[凶案替罪羊] 你在刀下倒下，却以杀手之身站起……"),
                    true
            );
            return false;
        });

        // 任意死亡：附近 4 格替罪羊触发发刀；卖花女清场
        OnPlayerDeath.EVENT.register((player, deathReason) -> {
            if (!(player instanceof ServerPlayer dead)) return;
            if (!(dead.level() instanceof ServerLevel level)) return;

            if (HabiRoles.isHabiRole(dead, HabiRoles.FLOWER_GIRL)) {
                FlowerGirlComponent.clearAllBouquets(level);
            }
            triggerNearbyScapegoats(level, dead);
        });

        OnPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return;

            if (killer instanceof ServerPlayer killerSp) {
                handleKillerSide(killerSp, dead, deathReason);
            }
        });

        OnDeathWithBody.EVENT.register((victim, killer, deathReason, body) -> {
            if (!(killer instanceof ServerPlayer killerSp)) return;
            if (!HabiRoles.isHabiRole(killerSp, HabiRoles.MIME_KILLER)) return;
            if (body instanceof PlayerBodyEntity bodyEntity) {
                MimeKillerComponent.hideBody(
                        bodyEntity,
                        MimeKillerComponent.BODY_HIDE_SECONDS * 20);
            }
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (world.isClientSide) return InteractionResultHolder.pass(stack);
            if (!HabiRoleItems.isPepperSpray(stack)) {
                return InteractionResultHolder.pass(stack);
            }
            if (!(player instanceof ServerPlayer)) {
                return InteractionResultHolder.pass(stack);
            }
            if (player.getCooldowns().isOnCooldown(stack.getItem())) {
                return InteractionResultHolder.pass(stack);
            }
            long until = world.getGameTime() + FlowerGirlComponent.MELEE_IMMUNE_SECONDS * 20L;
            FlowerGirlComponent.setMeleeImmune(player, until);
            world.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.PLAYERS,
                    1.0f,
                    1.2f
            );
            stack.shrink(1);
            player.getCooldowns().addCooldown(
                    Items.HONEY_BOTTLE,
                    FlowerGirlComponent.PEPPER_SPRAY_CD_SECONDS * 20
            );
            return InteractionResultHolder.sidedSuccess(stack, world.isClientSide());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MimeKillerComponent.tickHiddenBodies(server.getAllLevels());
        });

        HabiTrainCore.LOGGER.info("[HabiRoleEvents] listeners registered");
    }

    /**
     * 死者 4 格内的凶案替罪羊启动发刀窗口（带 30s 机制 CD）。
     */
    private static void triggerNearbyScapegoats(ServerLevel level, ServerPlayer dead) {
        if (level == null || dead == null) return;
        double rangeSq = CrimeScapegoatComponent.NEARBY_DEATH_RANGE * CrimeScapegoatComponent.NEARBY_DEATH_RANGE;
        for (ServerPlayer p : level.players()) {
            if (p == dead || p.isSpectator()) continue;
            if (!HabiRoles.isHabiRole(p, HabiRoles.CRIME_SCAPEGOAT)) continue;
            if (p.distanceToSqr(dead) > rangeSq) continue;
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

    private static void handleKillerSide(ServerPlayer killer, ServerPlayer victim, ResourceLocation deathReason) {
        if (HabiRoles.isHabiRole(killer, HabiRoles.SWIFT_WIND)) {
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
    }

    /**
     * 刀窗口内被杀 → 转随机杀手（参照赌徒 RoleUtils.changeRole）。
     */
    private static void convertScapegoatToRandomKiller(ServerPlayer victim, CrimeScapegoatComponent c) {
        List<SRERole> pool = CrimeScapegoatComponent.randomKillerPool();
        if (pool.isEmpty()) {
            HabiTrainCore.LOGGER.warn("[Scapegoat] no killer pool for conversion");
            return;
        }
        SRERole next = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        try {
            RoleUtils.changeRole(victim, next);
            c.markConverted();
            if (victim.level() instanceof ServerLevel level) {
                BlackoutRoleManager.reassignRole(
                        level, victim.getUUID(), next, BlackoutRoleManager.Faction.BAD);
            }
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
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[Scapegoat] conversion failed", t);
        }
    }
}
