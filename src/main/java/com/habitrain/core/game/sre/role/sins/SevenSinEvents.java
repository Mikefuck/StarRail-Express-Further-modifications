package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
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
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 七宗罪职业事件：分配时初始化 CCA；傲慢免疫/破防；嫉妒标记击杀门槛与掠夺；暴怒阶段机；懒惰沉睡/破盾。
 */
public final class SevenSinEvents {
    private SevenSinEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        ModdedRoleAssigned.EVENT.register((player, role) -> {
            if (player == null || role == null) return;
            if (!(player instanceof ServerPlayer sp)) return;
            ResourceLocation id = role.identifier();
            if (SevenSins.PRIDE_ID.equals(id)) {
                PrideComponent.KEY.get(sp).init();
            } else if (SevenSins.ENVY_ID.equals(id)) {
                EnvyComponent.KEY.get(sp).init();
            } else if (SevenSins.WRATH_ID.equals(id)) {
                WrathComponent.KEY.get(sp).init();
            } else if (SevenSins.GREED_ID.equals(id)) {
                GreedComponent.KEY.get(sp).init();
            } else if (SevenSins.GLUTTONY_ID.equals(id)) {
                GluttonyComponent.KEY.get(sp).init();
            } else if (SevenSins.LUST_ID.equals(id)) {
                LustComponent.KEY.get(sp).init();
            } else if (SevenSins.SLOTH_ID.equals(id)) {
                SlothComponent.KEY.get(sp).init();
            }
        });

        // Safe time end → sloth sleep + wrath first psycho (SRE + Blackout both fire OnGameTrueStarted).
        OnGameTrueStarted.EVENT.register(level -> {
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                // Natural Lust without lovers is demoted; forced Lust is kept for testing.
                if (game != null && SevenSins.LUST != null
                        && LustComponent.findTrueLoverPair(level) == null) {
                    boolean changed = false;
                    for (ServerPlayer player : level.players()) {
                        if (!game.isRole(player, SevenSins.LUST)) continue;
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
                }
                SlothComponent.onSafeTimeEnd(level);
                WrathComponent.onSafeTimeEnd(level);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[SevenSinEvents] onSafeTimeEnd failed", t);
            }
        });

        registerSlothInputLocks();
        registerEnvyAttackGate();
        registerWrathHitGate();
        registerGreedPouchHooks();

        // Pride aura + Envy gates + Wrath good-hit + Sloth gates.
        AllowPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(victim instanceof ServerPlayer dead)) return true;
            if (!(dead.level() instanceof ServerLevel level)) return true;

            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) return true;

            // Pride: cancel conventional weapon deaths while immune (non-force paths).
            if (SevenSins.PRIDE != null && game.isRole(dead, SevenSins.PRIDE)) {
                if (!SinDeathReasons.isForcePath(deathReason)
                        && SinDeathReasons.isConventionalWeapon(deathReason)
                        && PrideComponent.isPrideWeaponImmune(dead)) {
                    dead.setHealth(dead.getMaxHealth());
                    dead.displayClientMessage(Component.literal("§6[傲慢] 人群加持下，常规武器无法伤你。"), true);
                    HabiTrainCore.LOGGER.debug("[Pride] cancelled conventional death for {} reason={}",
                            dead.getGameProfile().getName(), deathReason);
                    return false;
                }
            }

            // Envy: no current mark → cannot kill anyone; richer than mark → cannot kill mark.
            if (killer instanceof ServerPlayer killerSp
                    && SevenSins.ENVY != null
                    && game.isRole(killerSp, SevenSins.ENVY)) {
                EnvyComponent envy = EnvyComponent.KEY.get(killerSp);
                if (envy != null) {
                    if (envy.getMarkedUuid() == null) {
                        dead.setHealth(dead.getMaxHealth());
                        killerSp.displayClientMessage(
                                Component.literal("§c[嫉妒] 未标记目标，无法击杀。"),
                                true
                        );
                        return false;
                    }
                    if (!envy.isMark(dead)) {
                        dead.setHealth(dead.getMaxHealth());
                        killerSp.displayClientMessage(
                                Component.literal("§c[嫉妒] 只能击杀当前标记的目标。"),
                                true
                        );
                        return false;
                    }
                    int envyBal = shopBalance(killerSp);
                    int targetBal = shopBalance(dead);
                    if (envyBal > targetBal) {
                        dead.setHealth(dead.getMaxHealth());
                        killerSp.displayClientMessage(
                                Component.literal("§c[嫉妒] 你比对方更有钱（你 " + envyBal
                                        + " > 对方 " + targetBal + "），无法击杀标记。"),
                                true
                        );
                        dead.displayClientMessage(
                                Component.literal("§e[嫉妒] 对方比你更有钱，标记未能致命。"),
                                true
                        );
                        return false;
                    }
                }
            }

            // Wrath: good-aligned hit triggers second psycho (cancels this death while protectable).
            if (killer instanceof ServerPlayer killerSp
                    && SevenSins.WRATH != null
                    && game.isRole(dead, SevenSins.WRATH)) {
                if (!SinDeathReasons.isForcePath(deathReason)) {
                    try {
                        WrathComponent wrath = WrathComponent.KEY.get(dead);
                        if (wrath != null && wrath.onHitByGood(dead, killerSp)) {
                            return false;
                        }
                    } catch (Throwable t) {
                        HabiTrainCore.LOGGER.warn("[Wrath] onHitByGood failed", t);
                    }
                }
            }

            // Sloth: sleeping shield absorb conventional hits; limited berserk may only kill attackers.
            if (SevenSins.SLOTH != null && game.isRole(dead, SevenSins.SLOTH)) {
                if (!SinDeathReasons.isForcePath(deathReason)
                        && SinDeathReasons.isConventionalWeapon(deathReason)) {
                    try {
                        SlothComponent sloth = SlothComponent.KEY.get(dead);
                        if (sloth != null && sloth.isSleeping()) {
                            ServerPlayer atk = killer instanceof ServerPlayer sp ? sp : null;
                            if (!sloth.onShieldHit(dead, atk)) {
                                return false;
                            }
                        }
                    } catch (Throwable t) {
                        HabiTrainCore.LOGGER.warn("[Sloth] shield absorb failed", t);
                    }
                }
            }

            // Sloth as killer: while limited berserk, only attackers set is legal.
            if (killer instanceof ServerPlayer killerSp
                    && SevenSins.SLOTH != null
                    && game.isRole(killerSp, SevenSins.SLOTH)) {
                try {
                    SlothComponent sloth = SlothComponent.KEY.get(killerSp);
                    if (sloth != null) {
                        if (sloth.isSleeping()) {
                            // Should already be blocked by input locks; hard gate.
                            dead.setHealth(dead.getMaxHealth());
                            return false;
                        }
                        if (sloth.isBerserk(level) && !sloth.isOpenBerserk(level)
                                && !sloth.canAttackTarget(level, dead.getUUID())) {
                            dead.setHealth(dead.getMaxHealth());
                            killerSp.displayClientMessage(
                                    Component.translatable("message.habitrain_core.sin_sloth.not_attacker"),
                                    true
                            );
                            return false;
                        }
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Sloth] attack gate failed", t);
                }
            }

            return true;
        });

        // Pride kill break + Envy mark loot + Sloth berserk kills.
        OnPlayerDeathWithKiller.EVENT.register((victim, killer, deathReason) -> {
            if (!(killer instanceof ServerPlayer killerSp)) return;
            if (!(killerSp.level() instanceof ServerLevel level)) return;

            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) return;

            if (SevenSins.PRIDE != null && game.isRole(killerSp, SevenSins.PRIDE)) {
                try {
                    PrideComponent.KEY.get(killerSp).onPrideKill(level);
                    killerSp.displayClientMessage(
                            Component.literal("§c[傲慢] 击杀破防 " + PrideComponent.BREAK_IMMUNE_SECONDS + " 秒！"),
                            true
                    );
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Pride] onPrideKill failed", t);
                }
            }

            if (victim instanceof ServerPlayer dead
                    && SevenSins.ENVY != null
                    && game.isRole(killerSp, SevenSins.ENVY)) {
                try {
                    EnvyComponent envy = EnvyComponent.KEY.get(killerSp);
                    if (envy != null && envy.isMark(dead)) {
                        handleEnvyMarkLoot(killerSp, dead);
                        // clear current mark after successful kill (history retained)
                        envy.setMarkedUuid(null);
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Envy] mark loot failed", t);
                }
            }

            if (SevenSins.SLOTH != null && game.isRole(killerSp, SevenSins.SLOTH)) {
                try {
                    SlothComponent sloth = SlothComponent.KEY.get(killerSp);
                    if (sloth != null && sloth.isBerserk(level)) {
                        sloth.onBerserkKill(killerSp);
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Sloth] onBerserkKill failed", t);
                }
            }
        });

        HabiTrainCore.LOGGER.info("[SevenSinEvents] pride + envy + wrath + sloth + greed hooks registered");
    }

    private static void registerGreedPouchHooks() {
        // Absorb: use pouch while other hand holds a sample item.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            if (!(player instanceof ServerPlayer sp)) {
                return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            ItemStack used = player.getItemInHand(hand);
            if (!GreedPouchItem.isBoundPouchOf(sp, used)) {
                return net.minecraft.world.InteractionResultHolder.pass(used);
            }
            InteractionHand otherHand = hand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND
                    : InteractionHand.MAIN_HAND;
            ItemStack other = player.getItemInHand(otherHand);
            if (other.isEmpty()) {
                sp.displayClientMessage(
                        Component.translatable("message.habitrain_core.sin_greed.need_item"),
                        true
                );
                return net.minecraft.world.InteractionResultHolder.fail(used);
            }
            try {
                GreedComponent greed = GreedComponent.KEY.get(sp);
                if (greed != null && greed.tryAbsorbOtherHand(sp, other)) {
                    return net.minecraft.world.InteractionResultHolder.success(used);
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Greed] absorb failed", t);
            }
            return net.minecraft.world.InteractionResultHolder.pass(used);
        });

        // NOTE: Do NOT register ShouldDropOnDeath here.
        // That event is OR/any-true (see SRE ShouldDropOnDeath). Returning true for
        // non-pouch stacks forced full inventory death drops and overrode the original
        // whitelist. Bound-pouch no-drop is handled by GreedPouchDropMixin.
    }

    private static void registerSlothInputLocks() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return net.minecraft.world.InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            // Sleep: full attack lock.
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return InteractionResult.FAIL;
            }
            // Ordinary melee attacks against a sleeping Sloth are shield hits, not health damage.
            if (player instanceof ServerPlayer attacker
                    && entity instanceof ServerPlayer target
                    && SlothComponent.isSleepingSloth(target)) {
                SlothComponent sloth = SlothComponent.KEY.get(target);
                if (sloth != null) {
                    sloth.onShieldHit(target, attacker);
                    return InteractionResult.FAIL;
                }
            }
            // Limited berserk: only attackers set; open berserk unrestricted.
            if (player instanceof ServerPlayer sp
                    && world instanceof ServerLevel level
                    && entity != null) {
                try {
                    if (SlothComponent.isSlothPlayer(level, sp)) {
                        SlothComponent sloth = SlothComponent.KEY.get(sp);
                        if (sloth != null
                                && sloth.isBerserk(level)
                                && !sloth.isOpenBerserk(level)
                                && !sloth.canAttackTarget(level, entity.getUUID())) {
                            sp.displayClientMessage(
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
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (SlothComponent.isSleepingSloth(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return true;
            if (SlothComponent.isSleepingSloth(player)) {
                notifySleepLock(player);
                return false;
            }
            return true;
        });
        try {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
                if (SlothComponent.isSleepingSloth(sender)) {
                    sender.displayClientMessage(
                            Component.translatable("message.habitrain_core.sin_sloth.chat_locked"),
                            true
                    );
                    return false;
                }
                return true;
            });
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Sloth] ServerMessageEvents.ALLOW_CHAT_MESSAGE unavailable", t);
        }
    }

    private static void registerEnvyAttackGate() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer attacker)) return InteractionResult.PASS;
            if (!(entity instanceof ServerPlayer target)) return InteractionResult.PASS;
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            try {
                SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
                if (game == null || SevenSins.ENVY == null || !game.isRole(attacker, SevenSins.ENVY)) {
                    return InteractionResult.PASS;
                }
                EnvyComponent envy = EnvyComponent.KEY.get(attacker);
                if (envy != null && !envy.canHarm(target)) {
                    attacker.displayClientMessage(
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
        });
    }

    private static void registerWrathHitGate() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer attacker)) return InteractionResult.PASS;
            if (!(entity instanceof ServerPlayer target)) return InteractionResult.PASS;
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            try {
                if (!WrathComponent.isWrathPlayer(level, target)) {
                    return InteractionResult.PASS;
                }
                WrathComponent wrath = WrathComponent.KEY.get(target);
                if (wrath != null && wrath.onHitByGood(target, attacker)) {
                    return InteractionResult.FAIL;
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Wrath] melee hit gate failed", t);
            }
            return InteractionResult.PASS;
        });
    }

    private static void notifySleepLock(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("message.habitrain_core.sin_sloth.input_locked"),
                    true
            );
        }
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
        // Prefer main/offhand first so death-drop races still have a shot at held items.
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
            if (i == inv.selected) continue; // already considered main hand
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

        // No transferable item → steal up to 100 coins
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
        /** Move one exact item, preserving its components/NBT. */
        ItemStack takeOne(Inventory inv) {
            ItemStack stack = switch (kind) {
                case MAIN -> inv.items.get(index);
                case OFF -> inv.offhand.get(index);
            };
            if (stack == null || stack.isEmpty() || stack.is(Items.AIR)) return ItemStack.EMPTY;
            ItemStack taken = stack.copyWithCount(1);
            if (taken.isEmpty() || taken.is(Items.AIR)) return ItemStack.EMPTY;
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
