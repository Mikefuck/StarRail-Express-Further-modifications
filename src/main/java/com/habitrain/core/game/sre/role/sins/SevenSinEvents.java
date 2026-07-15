package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.event.AllowPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 七宗罪职业事件：分配时初始化 CCA 状态机；傲慢免疫/破防；嫉妒标记击杀门槛与掠夺。
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

        // Pride aura + Envy mark balance gate (order: each handler independent).
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

            // Envy: marked victim only killable when envy gold <= target gold.
            if (killer instanceof ServerPlayer killerSp
                    && SevenSins.ENVY != null
                    && game.isRole(killerSp, SevenSins.ENVY)) {
                EnvyComponent envy = EnvyComponent.KEY.get(killerSp);
                if (envy != null && envy.isMark(dead)) {
                    int envyBal = shopBalance(killerSp);
                    int targetBal = shopBalance(dead);
                    if (envyBal > targetBal) {
                        dead.setHealth(dead.getMaxHealth());
                        killerSp.displayClientMessage(
                                Component.literal("§c[嫉妒] 目标金币不足（你 " + envyBal
                                        + " > 对方 " + targetBal + "），无法击杀标记。"),
                                true
                        );
                        dead.displayClientMessage(
                                Component.literal("§e[嫉妒] 对方比你更有钱，标记未能致命。"),
                                true
                        );
                        HabiTrainCore.LOGGER.debug(
                                "[Envy] blocked mark kill {} -> {} envyBal={} targetBal={}",
                                killerSp.getGameProfile().getName(),
                                dead.getGameProfile().getName(),
                                envyBal, targetBal
                        );
                        return false;
                    }
                }
            }

            return true;
        });

        // Pride kill break + Envy mark loot.
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
                        // clear mark after successful kill
                        envy.setMarkedUuid(null);
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[Envy] mark loot failed", t);
                }
            }
        });

        HabiTrainCore.LOGGER.info("[SevenSinEvents] pride + envy death/kill hooks registered");
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
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (EnvyComponent.isTransferable(stack, envy)) {
                candidates.add(new SlotRef(SlotKind.MAIN, i));
            }
        }
        for (int i = 0; i < inv.offhand.size(); i++) {
            ItemStack stack = inv.offhand.get(i);
            if (EnvyComponent.isTransferable(stack, envy)) {
                candidates.add(new SlotRef(SlotKind.OFF, i));
            }
        }

        if (!candidates.isEmpty()) {
            SlotRef pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            ItemStack taken = pick.takeOne(inv);
            if (taken != null && !taken.isEmpty()) {
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
        ItemStack takeOne(Inventory inv) {
            ItemStack stack = switch (kind) {
                case MAIN -> inv.items.get(index);
                case OFF -> inv.offhand.get(index);
            };
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack taken = stack.copyWithCount(1);
            stack.shrink(1);
            return taken;
        }
    }
}
