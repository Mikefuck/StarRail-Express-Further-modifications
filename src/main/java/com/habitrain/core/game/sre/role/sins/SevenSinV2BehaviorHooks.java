package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.RoleCombatHooks;
import com.habitrain.core.api.role.v2.behavior.RoleHookContext;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleInteractionHooks;
import com.habitrain.core.api.role.v2.behavior.RoleLifecycleHooks;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v2 managed behavior hooks for the seven sins (audit P0-3 / P1-1).
 *
 * <p>Replaces the process-global Fabric/SRE listeners in
 * {@link SevenSinEvents} with provider-scoped {@link RoleHooks}: component
 * init, safe-time lifecycle, sloth input locks, envy/wrath attack gates,
 * greed pouch absorption and all killer/victim combat gates. Chat lock remains
 * a small non-role global listener in {@link SevenSinEvents}.
 */
public final class SevenSinV2BehaviorHooks {

    private SevenSinV2BehaviorHooks() {}

    public static void registerWith(RoleExtensionRegistrar registrar) {
        if (registrar == null) {
            throw new IllegalArgumentException("registrar must not be null");
        }

        registrar.hooks(RoleKey.of(SevenSins.PRIDE_ID), RoleHooks.builder()
                .lifecycle(PRIDE_LIFE)
                .combat(PRIDE_COMBAT)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.ENVY_ID), RoleHooks.builder()
                .lifecycle(ENVY_LIFE)
                .interaction(ENVY_INTERACTION)
                .combat(ENVY_COMBAT)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.WRATH_ID), RoleHooks.builder()
                .lifecycle(WRATH_LIFE)
                .interaction(WRATH_INTERACTION)
                .combat(WRATH_COMBAT)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.GREED_ID), RoleHooks.builder()
                .lifecycle(GREED_LIFE)
                .interaction(GREED_INTERACTION)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.GLUTTONY_ID), RoleHooks.builder()
                .lifecycle(GLUTTONY_LIFE)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.LUST_ID), RoleHooks.builder()
                .lifecycle(LUST_LIFE)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.SLOTH_ID), RoleHooks.builder()
                .lifecycle(SLOTH_LIFE)
                .interaction(SLOTH_INTERACTION)
                .combat(SLOTH_COMBAT)
                .build());

        HabiTrainCore.LOGGER.info(
                "[SevenSins] v2 behavior hooks registered (lifecycle/combat/interaction, mutex)");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private static final RoleLifecycleHooks PRIDE_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            PrideComponent.KEY.get(player).init();
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks ENVY_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            EnvyComponent.KEY.get(player).init();
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks WRATH_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            WrathComponent.KEY.get(player).init();
        }

        @Override
        public void onGameTrueStart(ServerLevel level, RoleHookContext ctx) {
            WrathComponent.onSafeTimeEnd(level);
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks GREED_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            GreedComponent.KEY.get(player).init();
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks GLUTTONY_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            GluttonyComponent.KEY.get(player).init();
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks LUST_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            LustComponent.KEY.get(player).init();
        }

        @Override
        public void onGameTrueStart(ServerLevel level, RoleHookContext ctx) {
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                if (game == null || SevenSins.LUST == null
                        || LustComponent.findTrueLoverPair(level) != null) {
                    return;
                }
                boolean changed = false;
                for (ServerPlayer player : level.players()) {
                    if (!game.isRole(player, SevenSins.LUST)) {
                        continue;
                    }
                    if (SevenSinsMutex.isForcedSinPlayer(player, SevenSins.LUST)) {
                        HabiTrainCore.LOGGER.info(
                                "[Lust] forced keep for {} without lover pair",
                                player.getGameProfile().getName());
                        continue;
                    }
                    game.addRole(player, SevenSinsMutex.fallbackNonSin(SevenSins.LUST), true);
                    changed = true;
                    HabiTrainCore.LOGGER.warn(
                            "[Lust] removed at true start because no non-Lust mutual lover pair exists");
                }
                if (changed) {
                    game.syncRoles();
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[SevenSins] lust true-start demotion failed", t);
            }
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    private static final RoleLifecycleHooks SLOTH_LIFE = new RoleLifecycleHooks() {
        @Override
        public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
            SlothComponent.KEY.get(player).init();
        }

        @Override
        public void onGameTrueStart(ServerLevel level, RoleHookContext ctx) {
            SlothComponent.onSafeTimeEnd(level);
        }

        @Override
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    private static final RoleInteractionHooks ENVY_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult attackEntity(ServerPlayer player, Entity target,
                                              InteractionHand hand, RoleHookContext ctx) {
            if (!(target instanceof ServerPlayer targetPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return InteractionResult.PASS;
            }
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                if (game == null || SevenSins.ENVY == null || !game.isRole(player, SevenSins.ENVY)) {
                    return InteractionResult.PASS;
                }
                EnvyComponent envy = EnvyComponent.KEY.get(player);
                if (envy != null && !envy.canHarm(targetPlayer)) {
                    player.displayClientMessage(
                            Component.literal(envy.getMarkedUuid() == null
                                    ? "§c[嫉妒] 未标记目标，无法攻击。"
                                    : "§c[嫉妒] 只能攻击当前标记的目标。"),
                            true
                    );
                    return InteractionResult.FAIL;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Envy] attack gate failed", t);
            }
            return InteractionResult.PASS;
        }
    };

    private static final RoleInteractionHooks WRATH_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult attackEntity(ServerPlayer player, Entity target,
                                              InteractionHand hand, RoleHookContext ctx) {
            if (!(target instanceof ServerPlayer targetPlayer)) {
                return InteractionResult.PASS;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return InteractionResult.PASS;
            }
            try {
                if (!WrathComponent.isWrathPlayer(level, targetPlayer)) {
                    return InteractionResult.PASS;
                }
                WrathComponent wrath = WrathComponent.KEY.get(targetPlayer);
                if (wrath != null && wrath.onHitByGood(targetPlayer, player)) {
                    return InteractionResult.FAIL;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] melee hit gate failed", t);
            }
            return InteractionResult.PASS;
        }
    };

    private static final RoleInteractionHooks GREED_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                         InteractionHand hand, RoleHookContext ctx) {
            if (!GreedPouchItem.isBoundPouchOf(player, stack)) {
                return InteractionResult.PASS;
            }
            InteractionHand otherHand = hand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND
                    : InteractionHand.MAIN_HAND;
            ItemStack other = player.getItemInHand(otherHand);
            if (other.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.need_item"),
                        true
                );
                return InteractionResult.FAIL;
            }
            try {
                GreedComponent greed = GreedComponent.KEY.get(player);
                if (greed != null && greed.tryAbsorbOtherHand(player, other)) {
                    return InteractionResult.SUCCESS;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Greed] absorb failed", t);
            }
            return InteractionResult.PASS;
        }
    };

    private static final RoleInteractionHooks SLOTH_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult useItem(ServerPlayer player, ItemStack stack,
                                         InteractionHand hand, RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        @Override
        public InteractionResult useBlock(ServerPlayer player,
                                          net.minecraft.world.phys.BlockHitResult hit,
                                          InteractionHand hand, RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        @Override
        public InteractionResult useEntity(ServerPlayer player, Entity target,
                                           InteractionHand hand, RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        @Override
        public InteractionResult attackEntity(ServerPlayer player, Entity target,
                                              InteractionHand hand, RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            if (player instanceof ServerPlayer attacker
                    && target instanceof ServerPlayer targetPlayer
                    && SlothComponent.isSleepingSloth(targetPlayer)) {
                SlothComponent sloth = SlothComponent.KEY.get(targetPlayer);
                if (sloth != null) {
                    sloth.onShieldHit(targetPlayer, attacker);
                    return InteractionResult.FAIL;
                }
            }
            if (player.level() instanceof ServerLevel level) {
                try {
                    if (SlothComponent.isSlothPlayer(level, player)) {
                        SlothComponent sloth = SlothComponent.KEY.get(player);
                        if (sloth != null
                                && sloth.isBerserk(level)
                                && !sloth.isOpenBerserk(level)
                                && !sloth.canAttackTarget(level, target.getUUID())) {
                            player.displayClientMessage(
                                    Component.translatable("message.habitrain_core.sin_sloth.not_attacker"),
                                    true
                            );
                            return InteractionResult.FAIL;
                        }
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Sloth] limited berserk attack gate failed", t);
                }
            }
            return InteractionResult.PASS;
        }

        @Override
        public InteractionResult attackBlock(ServerPlayer player,
                                             net.minecraft.core.BlockPos pos,
                                             InteractionHand hand, RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        @Override
        public void breakBlock(ServerPlayer player, net.minecraft.core.BlockPos pos,
                               RoleHookContext ctx) {
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
            }
        }
    };

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    private static final RoleCombatHooks PRIDE_COMBAT = new RoleCombatHooks() {
        @Override
        public Decision allowDeathByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                           net.minecraft.resources.ResourceLocation deathReason,
                                           RoleHookContext ctx) {
            if (SevenSins.PRIDE == null || !(victim.level() instanceof ServerLevel level)) {
                return Decision.PASS;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || !game.isRole(victim, SevenSins.PRIDE)) {
                return Decision.PASS;
            }
            if (!SinDeathReasons.isForcePath(deathReason)
                    && SinDeathReasons.isConventionalWeapon(deathReason)
                    && PrideComponent.isPrideWeaponImmune(victim)) {
                victim.setHealth(victim.getMaxHealth());
                victim.displayClientMessage(
                        Component.literal("§6[傲慢] 人群加持下，常规武器无法伤你。"), true);
                HabiTrainCore.LOGGER.debug("[Pride] cancelled conventional death for {} reason={}",
                        victim.getGameProfile().getName(), deathReason);
                return Decision.DENY;
            }
            return Decision.PASS;
        }

        @Override
        public void onKill(ServerPlayer victim, @Nullable ServerPlayer killer,
                           net.minecraft.resources.ResourceLocation deathReason, RoleHookContext ctx) {
            if (killer == null) {
                return;
            }
            try {
                PrideComponent.KEY.get(killer).onPrideKill((ServerLevel) killer.level());
                killer.displayClientMessage(
                        Component.literal("§c[傲慢] 击杀破防 " + PrideComponent.BREAK_IMMUNE_SECONDS + " 秒！"),
                        true
                );
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Pride] onPrideKill failed", t);
            }
        }
    };

    private static final RoleCombatHooks ENVY_COMBAT = new RoleCombatHooks() {
        @Override
        public Decision allowKillByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                          net.minecraft.resources.ResourceLocation deathReason,
                                          RoleHookContext ctx) {
            if (killer == null || SevenSins.ENVY == null
                    || !(killer.level() instanceof ServerLevel level)) {
                return Decision.PASS;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || !game.isRole(killer, SevenSins.ENVY)) {
                return Decision.PASS;
            }
            try {
                EnvyComponent envy = EnvyComponent.KEY.get(killer);
                if (envy == null) {
                    return Decision.PASS;
                }
                if (envy.getMarkedUuid() == null) {
                    victim.setHealth(victim.getMaxHealth());
                    killer.displayClientMessage(
                            Component.literal("§c[嫉妒] 未标记目标，无法击杀。"), true);
                    return Decision.DENY;
                }
                if (!envy.isMark(victim)) {
                    victim.setHealth(victim.getMaxHealth());
                    killer.displayClientMessage(
                            Component.literal("§c[嫉妒] 只能击杀当前标记的目标。"), true);
                    return Decision.DENY;
                }
                int envyBal = shopBalance(killer);
                int targetBal = shopBalance(victim);
                if (envyBal > targetBal) {
                    victim.setHealth(victim.getMaxHealth());
                    killer.displayClientMessage(
                            Component.literal("§c[嫉妒] 你比对方更有钱（你 " + envyBal
                                    + " > 对方 " + targetBal + "），无法击杀标记。"),
                            true
                    );
                    victim.displayClientMessage(
                            Component.literal("§e[嫉妒] 对方比你更有钱，标记未能致命。"),
                            true
                    );
                    return Decision.DENY;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Envy] killer gate failed", t);
            }
            return Decision.PASS;
        }

        @Override
        public void onKill(ServerPlayer victim, @Nullable ServerPlayer killer,
                           net.minecraft.resources.ResourceLocation deathReason, RoleHookContext ctx) {
            if (killer == null) {
                return;
            }
            try {
                EnvyComponent envy = EnvyComponent.KEY.get(killer);
                if (envy != null && envy.isMark(victim)) {
                    handleEnvyMarkLoot(killer, victim);
                    envy.setMarkedUuid(null);
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Envy] mark loot failed", t);
            }
        }
    };

    private static final RoleCombatHooks WRATH_COMBAT = new RoleCombatHooks() {
        @Override
        public Decision allowDeathByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                           net.minecraft.resources.ResourceLocation deathReason,
                                           RoleHookContext ctx) {
            if (SevenSins.WRATH == null || !(victim.level() instanceof ServerLevel level)) {
                return Decision.PASS;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || !game.isRole(victim, SevenSins.WRATH)) {
                return Decision.PASS;
            }
            if (SinDeathReasons.isForcePath(deathReason) || !(killer instanceof ServerPlayer killerSp)) {
                return Decision.PASS;
            }
            try {
                WrathComponent wrath = WrathComponent.KEY.get(victim);
                if (wrath != null && wrath.onHitByGood(victim, killerSp)) {
                    return Decision.DENY;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] onHitByGood failed", t);
            }
            return Decision.PASS;
        }
    };

    private static final RoleCombatHooks SLOTH_COMBAT = new RoleCombatHooks() {
        @Override
        public Decision allowDeathByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                           net.minecraft.resources.ResourceLocation deathReason,
                                           RoleHookContext ctx) {
            if (SevenSins.SLOTH == null || !(victim.level() instanceof ServerLevel level)) {
                return Decision.PASS;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || !game.isRole(victim, SevenSins.SLOTH)) {
                return Decision.PASS;
            }
            if (!SinDeathReasons.isForcePath(deathReason)
                    && SinDeathReasons.isConventionalWeapon(deathReason)) {
                try {
                    SlothComponent sloth = SlothComponent.KEY.get(victim);
                    if (sloth != null && sloth.isSleeping()) {
                        ServerPlayer atk = killer instanceof ServerPlayer sp ? sp : null;
                        if (!sloth.onShieldHit(victim, atk)) {
                            return Decision.DENY;
                        }
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Sloth] shield absorb failed", t);
                }
            }
            return Decision.PASS;
        }

        @Override
        public Decision allowKillByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                          net.minecraft.resources.ResourceLocation deathReason,
                                          RoleHookContext ctx) {
            if (killer == null || SevenSins.SLOTH == null
                    || !(killer.level() instanceof ServerLevel level)) {
                return Decision.PASS;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null || !game.isRole(killer, SevenSins.SLOTH)) {
                return Decision.PASS;
            }
            try {
                SlothComponent sloth = SlothComponent.KEY.get(killer);
                if (sloth == null) {
                    return Decision.PASS;
                }
                if (sloth.isSleeping()) {
                    victim.setHealth(victim.getMaxHealth());
                    return Decision.DENY;
                }
                if (sloth.isBerserk(level) && !sloth.isOpenBerserk(level)
                        && !sloth.canAttackTarget(level, victim.getUUID())) {
                    victim.setHealth(victim.getMaxHealth());
                    killer.displayClientMessage(
                            Component.translatable("message.habitrain_core.sin_sloth.not_attacker"),
                            true
                    );
                    return Decision.DENY;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Sloth] attack gate failed", t);
            }
            return Decision.PASS;
        }

        @Override
        public void onKill(ServerPlayer victim, @Nullable ServerPlayer killer,
                           net.minecraft.resources.ResourceLocation deathReason, RoleHookContext ctx) {
            if (killer == null) {
                return;
            }
            try {
                SlothComponent sloth = SlothComponent.KEY.get(killer);
                ServerLevel level = (ServerLevel) killer.level();
                if (sloth != null && sloth.isBerserk(level)) {
                    sloth.onBerserkKill(killer);
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Sloth] onBerserkKill failed", t);
            }
        }
    };

    // ------------------------------------------------------------------
    // Helpers (kept in the v2 class; chat-lock-only SevenSinEvents remains)
    // ------------------------------------------------------------------

    private static void notifySleepLock(ServerPlayer player) {
        player.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.input_locked"),
                true
        );
    }

    private static int shopBalance(ServerPlayer player) {
        try {
            SREPlayerShopComponent shop = SREPlayerShopComponent.KEY.get(player);
            return shop != null ? shop.balance : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void handleEnvyMarkLoot(ServerPlayer envy, ServerPlayer victim) {
        List<SlotRef> candidates = new ArrayList<>();
        Inventory inv = victim.getInventory();
        if (EnvyComponent.isTransferable(victim.getMainHandItem(), envy)) {
            candidates.add(new SlotRef(SlotKind.MAIN, inv.selected));
        }
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (EnvyComponent.isTransferable(stack, envy)) {
                candidates.add(new SlotRef(SlotKind.OFF, i));
            }
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == inv.selected) {
                continue;
            }
            ItemStack stack = inv.items.get(i);
            if (EnvyComponent.isTransferable(stack, envy)) {
                candidates.add(new SlotRef(SlotKind.MAIN, i));
            }
        }

        while (!candidates.isEmpty()) {
            int idx = ThreadLocalRandom.current().nextInt(candidates.size());
            SlotRef pick = candidates.remove(idx);
            ItemStack taken = pick.takeOne(inv);
            if (taken == null || taken.isEmpty() || taken.is(Items.AIR)) {
                continue;
            }
            if (!envy.getInventory().add(taken)) {
                envy.drop(taken, false);
            }
            envy.displayClientMessage(
                    Component.literal("§a[嫉妒] 从标记目标夺得 " + taken.getHoverName().getString() + "。"),
                    true
            );
            inv.setChanged();
            return;
        }

        int victimBal = shopBalance(victim);
        int steal = Math.min(EnvyComponent.COIN_STEAL_MAX, Math.max(0, victimBal));
        if (steal <= 0) {
            envy.displayClientMessage(Component.literal("§e[嫉妒] 标记目标无可掠夺物与金币。"), true);
            return;
        }
        try {
            SREPlayerShopComponent vShop = SREPlayerShopComponent.KEY.get(victim);
            SREPlayerShopComponent eShop = SREPlayerShopComponent.KEY.get(envy);
            if (vShop != null) {
                vShop.setBalance(Math.max(0, vShop.balance - steal));
            }
            if (eShop != null) {
                eShop.addToBalance(steal);
            }
            envy.displayClientMessage(
                    Component.literal("§a[嫉妒] 从标记目标掠夺 " + steal + " 金币。"),
                    true
            );
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Envy] coin steal failed", t);
        }
    }

    private enum SlotKind { MAIN, OFF }

    private record SlotRef(SlotKind kind, int index) {
        ItemStack takeOne(Inventory inv) {
            ItemStack stack = switch (kind) {
                case MAIN -> inv.items.get(index);
                case OFF -> inv.offhand.get(index);
            };
            if (stack == null || stack.isEmpty() || stack.is(Items.AIR)) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = stack.copyWithCount(1);
            if (taken.isEmpty() || taken.is(Items.AIR)) {
                return ItemStack.EMPTY;
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                switch (kind) {
                    case MAIN -> inv.items.set(index, ItemStack.EMPTY);
                    case OFF -> inv.offhand.set(index, ItemStack.EMPTY);
                }
            }
            return taken;
        }
    }
}
