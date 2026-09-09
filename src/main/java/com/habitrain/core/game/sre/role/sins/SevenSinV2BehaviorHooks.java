package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoles;
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
                .combat(new RoleCombatHooks() {
                    @Override
                    public void onDeath(ServerPlayer player, net.minecraft.resources.ResourceLocation reason,
                                        RoleHookContext ctx) {
                        com.habitrain.core.game.sre.role.sins.component.GreedEconomy.distributeEstate(player);
                    }
                })
                .build());
        registrar.hooks(RoleKey.of(SevenSins.GLUTTONY_ID), RoleHooks.builder()
                .lifecycle(GLUTTONY_LIFE)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.LUST_ID), RoleHooks.builder()
                .lifecycle(LUST_LIFE)
                .build());
        registrar.hooks(RoleKey.of(SevenSins.SLOTH_ID), RoleHooks.builder()
                .lifecycle(SLOTH_LIFE)
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
            WrathComponent.onRoundReady(level);
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
                    if (!HabiRoles.isHabiRole(player, SevenSins.LUST)) {
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
        public void onRolesConfirm(ServerLevel level, Map<Player, io.wifi.starrailexpress.api.SRERole> roles,
                                   RoleHookContext ctx) {
            SevenSinsMutex.beforeAssign(level, roles);
        }
    };

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    private static final RoleInteractionHooks WRATH_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult attackBlock(ServerPlayer player, net.minecraft.core.BlockPos pos,
                                             InteractionHand hand, RoleHookContext ctx) {
            try {
                WrathComponent wrath = WrathComponent.KEY.get(player);
                return wrath == null
                        ? InteractionResult.PASS
                        : wrath.tryPryDoorWithBat(player, pos, hand);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] bat door-pry failed", t);
                return InteractionResult.PASS;
            }
        }
    };

    private static final RoleInteractionHooks GREED_INTERACTION = new RoleInteractionHooks() {
        @Override
        public InteractionResult useEntity(ServerPlayer player, @Nullable Entity target,
                                           InteractionHand hand, RoleHookContext ctx) {
            if (!(target instanceof ServerPlayer other)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
            return com.habitrain.core.game.sre.role.sins.component.GreedInventoryMenu.open(player, other)
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

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
            if (game == null || !HabiRoles.isHabiRole(victim, SevenSins.PRIDE)) {
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
        public void onKill(ServerPlayer victim, @Nullable ServerPlayer killer,
                           net.minecraft.resources.ResourceLocation deathReason, RoleHookContext ctx) {
            if (killer == null) {
                return;
            }
            try {
                handleEnvyKillLoot(killer, victim);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Envy] guaranteed kill loot failed", t);
            }
        }
    };

    private static final RoleCombatHooks WRATH_COMBAT = new RoleCombatHooks() {
        @Override
        public void onAnyDeath(ServerPlayer dead,
                               net.minecraft.resources.ResourceLocation deathReason,
                               RoleHookContext ctx) {
            try {
                WrathComponent.onAnyPlayerDeath(dead);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] teammate-death handling failed", t);
            }
        }
    };

    // ------------------------------------------------------------------
    // Helpers (kept in the v2 class; chat-lock-only SevenSinEvents remains)
    // ------------------------------------------------------------------

    private static void handleEnvyKillLoot(ServerPlayer envy, ServerPlayer victim) {
        List<SlotRef> candidates = new ArrayList<>();
        Inventory inv = victim.getInventory();
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (stack != null && !stack.isEmpty() && !stack.is(Items.AIR)) {
                candidates.add(new SlotRef(SlotKind.OFF, i));
            }
        }
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack != null && !stack.isEmpty() && !stack.is(Items.AIR)) {
                candidates.add(new SlotRef(SlotKind.MAIN, i));
            }
        }
        for (int i = 0; i < inv.armor.size(); i++) {
            ItemStack stack = inv.armor.get(i);
            if (stack != null && !stack.isEmpty() && !stack.is(Items.AIR)) {
                candidates.add(new SlotRef(SlotKind.ARMOR, i));
            }
        }

        boolean itemGranted = false;
        while (!candidates.isEmpty()) {
            int idx = ThreadLocalRandom.current().nextInt(candidates.size());
            SlotRef pick = candidates.remove(idx);
            ItemStack taken = pick.takeOne(inv);
            if (taken == null || taken.isEmpty() || taken.is(Items.AIR)) {
                continue;
            }
            Component itemName = taken.getHoverName().copy();
            if (!envy.getInventory().add(taken)) {
                envy.drop(taken, false);
            }
            envy.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_envy.item_gain",
                            itemName),
                    true
            );
            inv.setChanged();
            itemGranted = true;
            break;
        }

        if (!itemGranted) {
            envy.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_envy.no_item"), true);
        }

        try {
            SREPlayerShopComponent eShop = SREPlayerShopComponent.KEY.get(envy);
            if (eShop != null) {
                eShop.addToBalance(EnvyComponent.KILL_BONUS_COINS);
            }
            envy.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_envy.coin_gain",
                            EnvyComponent.KILL_BONUS_COINS),
                    true
            );
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Envy] guaranteed coin grant failed", t);
        }
    }

    private enum SlotKind { MAIN, OFF, ARMOR }

    private record SlotRef(SlotKind kind, int index) {
        ItemStack takeOne(Inventory inv) {
            ItemStack stack = switch (kind) {
                case MAIN -> inv.items.get(index);
                case OFF -> inv.offhand.get(index);
                case ARMOR -> inv.armor.get(index);
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
                    case ARMOR -> inv.armor.set(index, ItemStack.EMPTY);
                }
            }
            return taken;
        }
    }
}
