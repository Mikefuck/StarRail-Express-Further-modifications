package com.habitrain.core.game.blackout.shop;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.blackout.ExclusiveTaskHudSync;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 停电任务商店服务 — 处理红色电话 {@code decocraft:rotary_phone_red} 的购买逻辑。
 *
 * 核心契约（用户需求）：
 * <ul>
 *   <li>校验：停电模式对局中、存活、阵营匹配、余额足够、一次性/炸毁后锁定等</li>
 *   <li>扣款</li>
 *   <li>{@link #cancelWithoutReward} — 强制取消当前停电任务 + 原版 SRE 任务，不发奖</li>
 *   <li>派发购买的停电任务（或发放临时电源提灯）</li>
 * </ul>
 */
public final class BlackoutTaskShopService {

    /** 临时电源提灯耐久：60 点，每秒耗 1 点 = 1 分钟。 */
    public static final int TEMP_POWER_MAX_DAMAGE = 60;
    /** NBT 标记 key：标识这是商店临时电源提灯，到期需回收。 */
    public static final String TEMP_POWER_TAG = "habitrain_core:temp_power";

    private BlackoutTaskShopService() {}

    /** 判断玩家阵营可见哪些条目（用于构建商店目录快照）。 */
    public static List<BlackoutTaskShopCatalog.Entry> visibleEntries(ServerLevel level, ServerPlayer player) {
        BlackoutRoleManager.Faction faction = BlackoutRoleManager.getFaction(level, player.getUUID());
        if (faction == null) return List.of();
        boolean destroyed = BlackoutTaskShopState.isGeneratorDestroyed(level);
        boolean permanentBlackout = BlackoutTimerSystem.isPermanentBlackoutActive(level);
        boolean blackoutPhase = isBlackoutPhaseActive(level);
        List<BlackoutTaskShopCatalog.Entry> result = new ArrayList<>();
        for (BlackoutTaskShopCatalog.Entry e : BlackoutTaskShopCatalog.ALL) {
            // 临时电源：进入停电阶段后即可买（不要求炸发电机）
            if (e.kind() == BlackoutTaskShopCatalog.Kind.TEMP_POWER) {
                if (!blackoutPhase) continue;
            } else {
                if (e.onlyAfterDestroy() && !destroyed) continue;
            }
            if (e.hideAfterDestroy() && destroyed) continue;
            // 永久停电阶段禁止延长供电三件套（修理线路/维持供电/添煤）
            if (permanentBlackout && isExtendPowerEntry(e)) continue;
            if (e.faction() == BlackoutTaskShopCatalog.Faction.GOOD && faction != BlackoutRoleManager.Faction.GOOD) continue;
            if (e.faction() == BlackoutTaskShopCatalog.Faction.BAD && faction != BlackoutRoleManager.Faction.BAD) continue;
            result.add(e);
        }
        return result;
    }

    /** 构建购买校验失败原因，null 表示可买。 */
    public static String purchaseBlockReason(ServerLevel level, ServerPlayer player, BlackoutTaskShopCatalog.Entry entry) {
        if (level == null || player == null || entry == null) return "无效请求";
        if (!isBlackoutRunning(level)) return "非停电模式";
        if (player.isSpectator() || !BlackoutRoleManager.isInteractable(level, player.getUUID())) return "已淘汰";
        // C2S 必须先在红色电话处打开过商店，且仍在交互距离内
        String gate = com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.validate(
                com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.Kind.TASK_SHOP, level, player);
        if (gate != null) return gate;

        BlackoutRoleManager.Faction faction = BlackoutRoleManager.getFaction(level, player.getUUID());
        if (faction == null) return "已淘汰";
        if (entry.faction() == BlackoutTaskShopCatalog.Faction.GOOD && faction != BlackoutRoleManager.Faction.GOOD)
            return "仅好人可购买";
        if (entry.faction() == BlackoutTaskShopCatalog.Faction.BAD && faction != BlackoutRoleManager.Faction.BAD)
            return "仅坏人可购买";

        boolean destroyed = BlackoutTaskShopState.isGeneratorDestroyed(level);
        if (entry.kind() == BlackoutTaskShopCatalog.Kind.TEMP_POWER) {
            if (!isBlackoutPhaseActive(level)) return "当前未停电";
        } else if (entry.onlyAfterDestroy() && !destroyed) {
            return "需先炸毁发电机";
        }
        if (entry.hideAfterDestroy() && destroyed) return "发电机已毁，无法接取";

        // 永久停电阶段禁止延长供电三件套
        if (BlackoutTimerSystem.isPermanentBlackoutActive(level) && isExtendPowerEntry(entry)) {
            return "停电中无法接取延长供电任务，请先恢复供电";
        }

        if (entry.oncePerGame() && BlackoutTaskShopState.isFurnaceExplosionTaken(level))
            return "本局已接取过炸毁发电机";

        if (entry.kind() == BlackoutTaskShopCatalog.Kind.TASK
                && TaskRegistry.get(entry.key()) == null) {
            return "任务暂不可用";
        }

        int price = entry.resolvePrice();
        var shop = SREPlayerShopComponent.KEY.get(player);
        int balance = shop != null ? shop.balance : 0;
        if (balance < price) return "金币不足(" + balance + "/" + price + ")";

        // 临时电源：已有提灯则拒绝（不叠加）
        if (entry.kind() == BlackoutTaskShopCatalog.Kind.TEMP_POWER) {
            if (BlackoutTaskShopState.hasTempPower(level, player.getUUID())
                    || playerHasTempLantern(player)) {
                return "已持有临时电源，不叠加";
            }
        }
        return null;
    }

    /**
     * 执行购买。返回 null=成功，否则为失败原因文本。
     */
    public static String tryPurchase(ServerLevel level, ServerPlayer player, BlackoutTaskShopCatalog.Entry entry) {
        String block = purchaseBlockReason(level, player, entry);
        if (block != null) return block;

        int price = entry.resolvePrice();
        var shop = SREPlayerShopComponent.KEY.get(player);
        if (shop == null) return "商店组件缺失";

        // 扣款
        shop.addToBalance(-price);
        com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.touch(
                com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.Kind.TASK_SHOP, player);

        if (entry.kind() == BlackoutTaskShopCatalog.Kind.TEMP_POWER) {
            grantTempPower(level, player);
            SubtitleNotifier.sendTop(player,
                    Component.literal("§a临时电源"),
                    Component.literal("§a已获得提灯，1 分钟后损坏。"),
                    80);
            return null;
        }

        // 任务条目：先无奖取消当前任务，再派发
        cancelWithoutReward(level, player);
        assignPaidTask(level, player, entry);
        // 炸毁发电机不弹「已接取」提示，其它商店任务仍提示
        if (!BlackoutTaskShopCatalog.FURNACE_EXPLOSION.key().equals(entry.key())) {
            SubtitleNotifier.sendTop(player,
                    Component.literal("§a任务商店"),
                    Component.literal("§a已接取 §e" + entry.displayName() + " §a(花费 " + price + " 金)"),
                    80);
        }
        return null;
    }

    /**
     * 强制取消当前活跃任务，不发奖：
     * <ul>
     *   <li>停电专属任务：onRemove + 回收道具，不调 onComplete / 不发奖</li>
     *   <li>原版 SRE 任务：clear()（SREPlayerTaskComponent）</li>
     * </ul>
     */
    public static void cancelWithoutReward(ServerLevel level, ServerPlayer player) {
        TaskManager mgr = TaskManager.getInstance();
        UUID uuid = player.getUUID();

        // 停电专属任务（activeCustomTasks）
        TaskInstance active = mgr.getActiveTask(uuid);
        if (active != null) {
            try {
                ItemReclaimHelper.reclaimForTask(player, active);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("[TaskShop] reclaim active failed", t);
            }
            try {
                active.getDefinition().onRemove(player, active);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("[TaskShop] onRemove active failed", t);
            }
            mgr.removeActiveTask(uuid);
            ActiveTaskPayload.clearForPlayer(player);
            ExclusiveTaskHudSync.clear(player);
        }

        // 假任务（杀手双任务机制已关闭，保留兼容清理）
        TaskInstance fake = mgr.getFakeTask(uuid);
        if (fake != null) {
            try {
                ItemReclaimHelper.reclaimForTask(player, fake);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("[TaskShop] reclaim fake failed", t);
            }
            try {
                fake.getDefinition().onRemove(player, fake);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("[TaskShop] onRemove fake failed", t);
            }
            mgr.removeFakeTask(uuid);
            ActiveTaskPayload.clearForPlayer(player, true);
        }

        // 原版 SRE 任务
        try {
            var taskComp = SREPlayerTaskComponent.KEY.get(player);
            if (taskComp != null) taskComp.clear();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("[TaskShop] clear SRE tasks failed", t);
        }

        mgr.clearBlackoutRotationFlag(uuid);
    }

    /** 派发购买的停电任务。 */
    private static void assignPaidTask(ServerLevel level, ServerPlayer player, BlackoutTaskShopCatalog.Entry entry) {
        TaskDefinition def = TaskRegistry.get(entry.key());
        if (def == null) {
            HabiTrainCore.LOGGER.error("[TaskShop] task definition not found: {}", entry.key());
            return;
        }
        TaskManager mgr = TaskManager.getInstance();
        TaskInstance instance = new TaskInstance(def);
        def.onAssign(player, instance);
        mgr.setActiveTask(player.getUUID(), instance);

        if (entry.oncePerGame()) {
            BlackoutTaskShopState.markFurnaceExplosionTaken(level);
        }

        ActiveTaskPayload.sendToPlayer(player, def.getFullId());
        ExclusiveTaskHudSync.insert(player, instance);
    }

    /** 发放临时电源提灯（MC 耐久条 60 点 = 1 分钟）。 */
    private static void grantTempPower(ServerLevel level, ServerPlayer player) {
        ItemStack lantern = new ItemStack(Items.LANTERN, 1);
        lantern.set(DataComponents.CUSTOM_NAME, Component.literal("§e临时电源提灯"));
        // 1.21 耐久组件：MAX_DAMAGE + DAMAGE
        lantern.set(DataComponents.MAX_DAMAGE, TEMP_POWER_MAX_DAMAGE);
        lantern.set(DataComponents.DAMAGE, 0);
        // CUSTOM_DATA 标记
        net.minecraft.nbt.CompoundTag tag = lantern.getOrDefault(
                DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(TEMP_POWER_TAG, true);
        lantern.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        boolean added = player.getInventory().add(lantern);
        if (!added) player.drop(lantern, false);

        // 服务端到期 tick 是权威时间；玩家离线时 gameTime 仍推进，因此不能暂停寿命。
        long expiry = level.getGameTime() + TEMP_POWER_MAX_DAMAGE * 20L;
        BlackoutTaskShopState.setTempPower(level, player.getUUID(), expiry);
    }

    /** 玩家背包中是否已持有带临时电源标记的提灯。 */
    private static boolean playerHasTempLantern(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isTempLantern(stack)) return true;
        }
        return false;
    }

    private static boolean isTempLantern(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.LANTERN)) return false;
        var cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().contains(TEMP_POWER_TAG);
    }

    /**
     * 每秒 tick：按权威到期 tick 校准提灯耐久；离线时间照常流逝。
     * 到期玩家下次在线时销毁提灯并清理状态。
     */
    public static void tickTempPower(ServerLevel level) {
        var entries = BlackoutTaskShopState.tempPowerEntries(level);
        for (var it = entries.iterator(); it.hasNext(); ) {
            var e = it.next();
            UUID uuid = e.getKey();
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);
            if (sp == null) continue;

            long remainingTicks = e.getValue() - level.getGameTime();
            if (remainingTicks <= 0L) {
                reclaimTempLantern(sp);
                it.remove();
                SubtitleNotifier.sendTop(sp,
                        Component.literal("§e临时电源"),
                        Component.literal("§e提灯已损坏。"),
                        60);
                continue;
            }

            boolean stillHas = false;
            int remainingSeconds = (int) Math.max(1L, (remainingTicks + 19L) / 20L);
            for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                ItemStack stack = sp.getInventory().getItem(i);
                if (!isTempLantern(stack)) continue;
                stillHas = true;
                Integer maxD = stack.get(DataComponents.MAX_DAMAGE);
                int max = maxD != null ? maxD : TEMP_POWER_MAX_DAMAGE;
                stack.set(DataComponents.DAMAGE,
                        Math.max(0, max - Math.min(max, remainingSeconds)));
            }

            if (!stillHas) {
                it.remove();
            }
        }
    }

    /** 移除玩家背包中所有临时电源提灯（局末/离线清理用）。 */
    public static void reclaimTempLantern(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isTempLantern(stack)) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static boolean isBlackoutRunning(ServerLevel level) {
        var gm = GameModeRegistry.getActiveForLevel(level);
        return gm.isPresent() && "habitrain:blackout".equals(gm.get().getId());
    }

    /**
     * 是否已进入可买临时电源的停电相关阶段：
     * FIRST_BLACKOUT / SECOND_BLACKOUT（永久停电中），或短暂停电中。
     * 炸发电机前后不互斥——只要阶段满足即可买。
     */
    private static boolean isBlackoutPhaseActive(ServerLevel level) {
        if (BlackoutTimerSystem.isPermanentBlackoutActive(level)) return true;
        try {
            var sreBlackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(level);
            if (sreBlackout != null && sreBlackout.isBlackoutActive()) return true;
        } catch (Throwable ignored) {}
        // 也允许 transient（短暂停电）时购买
        BlackoutTimerSystem.Phase phase = BlackoutTimerSystem.getPhase(level);
        return phase == BlackoutTimerSystem.Phase.FIRST_BLACKOUT
                || phase == BlackoutTimerSystem.Phase.SECOND_BLACKOUT;
    }

    /** 延长供电三件套：修理线路 / 维持供电 / 添煤（停电中无效且不应出售）。 */
    private static boolean isExtendPowerEntry(BlackoutTaskShopCatalog.Entry entry) {
        if (entry == null || entry.kind() != BlackoutTaskShopCatalog.Kind.TASK) return false;
        String key = entry.key();
        return HabiTrainCore.TASK_REPAIR_WIRING.equals(key)
                || HabiTrainCore.TASK_MAINTAIN_POWER.equals(key)
                || HabiTrainCore.TASK_ADD_COAL.equals(key);
    }
}
