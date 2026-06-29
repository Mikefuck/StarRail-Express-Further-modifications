package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 停电模式 — TACZ 枪械桥接
 *
 * 职责:
 * 1. 监听 TACZ 子弹击中事件 → 取消原版伤害 → 触发 SRE 游戏死亡 (淘汰玩家)
 * 2. 警长通过 /habi_api buy_gun / buy_ammo 购买沙漠之鹰和子弹
 * 3. 购买使用 SRE 原版货币系统 (SREPlayerShopComponent.balance)
 */
public class TACZWeaponBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("TACZBridge");

    // TACZ 物品 ID (根据 TACZ 资源索引文件)
    public static final String DESERT_EAGLE_ID = "tacz:deagle";
    public static final String AMMO_50AE_ID = "tacz:ammo_50ae";

    public static final int DESERT_EAGLE_PRICE = 50;
    public static final int AMMO_PRICE = 50;

    /** 本局已购买过沙漠之鹰的警长 (每局限购一次) */
    private static final Set<UUID> DESERT_EAGLE_PURCHASED = new HashSet<>();
    private static boolean eventRegistered = false;

    /** 注册 TACZ 桥接 (在 BlackoutMode.onStart 中调用) */
    public static void register() {
        if (eventRegistered) return;
        eventRegistered = true;

        // 子弹击中监听: 警长沙漠之鹰 → SRE 游戏死亡
        EntityHurtByGunEvent.PRE.register(event -> {
            if (event.getLogicalSide().isClient()) return;
            if (!(event.getHurtEntity() instanceof ServerPlayer target)) return;
            if (!(event.getAttacker() instanceof ServerPlayer shooter)) return;

            var level = target.serverLevel();
            var activeMode = GameModeRegistry.getActiveForLevel(level);
            if (activeMode.isEmpty() || !(activeMode.get() instanceof BlackoutMode)) return;

            if (!BlackoutRoleManager.isSheriff(shooter.getUUID())) return;
            if (!BlackoutRoleManager.isAlive(target.getUUID())) return;

            event.setCanceled(true);
            eliminatePlayer(target, shooter);
        });

        LOGGER.info("TACZWeaponBridge registered (bullet hit listener)");
    }

    // ====================== 购买 (通过 /habi_api 命令) ======================

    /**
     * 警长购买沙漠之鹰 (每局限购一次)
     * @return true=购买成功
     */
    public static boolean buyDesertEagle(ServerPlayer player) {
        if (!BlackoutRoleManager.isSheriff(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c只有警长才能购买沙漠之鹰！"));
            return false;
        }
        if (DESERT_EAGLE_PURCHASED.contains(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你本局已经购买过沙漠之鹰了！"));
            return false;
        }

        // 检查余额
        var shop = SREPlayerShopComponent.KEY.get(player);
        if (shop.balance < DESERT_EAGLE_PRICE) {
            player.sendSystemMessage(Component.literal(
                "§c余额不足！需要 " + DESERT_EAGLE_PRICE + " 币，你只有 " + shop.balance + " 币"));
            return false;
        }

        // 创建物品
        ItemStack stack = createGunStack(DESERT_EAGLE_ID, 1);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c沙漠之鹰物品未找到，请联系管理员"));
            LOGGER.error("Desert Eagle item not found: {}", DESERT_EAGLE_ID);
            return false;
        }

        // 扣款 + 给物品
        shop.addToBalance(-DESERT_EAGLE_PRICE);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        DESERT_EAGLE_PURCHASED.add(player.getUUID());

        player.sendSystemMessage(Component.literal(
            "§a✔ 购买成功！花费 " + DESERT_EAGLE_PRICE + " 币获得了沙漠之鹰"));
        LOGGER.info("Sheriff {} bought Desert Eagle for {} coins",
            player.getName().getString(), DESERT_EAGLE_PRICE);
        return true;
    }

    /**
     * 警长购买子弹
     * @param count 购买数量 (一组=4发)
     * @return true=购买成功
     */
    public static boolean buyAmmo(ServerPlayer player, int count) {
        if (!BlackoutRoleManager.isSheriff(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c只有警长才能购买弹药！"));
            return false;
        }

        int totalPrice = AMMO_PRICE * count;

        var shop = SREPlayerShopComponent.KEY.get(player);
        if (shop.balance < totalPrice) {
            player.sendSystemMessage(Component.literal(
                "§c余额不足！需要 " + totalPrice + " 币 (" + AMMO_PRICE + "/发 × " + count + ")，你只有 " + shop.balance + " 币"));
            return false;
        }

        ItemStack stack = createGunStack(AMMO_50AE_ID, count);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c弹药物品未找到，请联系管理员"));
            LOGGER.error("Ammo {} not found", AMMO_50AE_ID);
            return false;
        }

        shop.addToBalance(-totalPrice);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }

        player.sendSystemMessage(Component.literal(
            "§a✔ 花费 " + totalPrice + " 币购买了 " + count + " 发弹药"));
        return true;
    }

    // ====================== 内部工具 ======================

    private static ItemStack createGunStack(String id, int count) {
        ResourceLocation location = ResourceLocation.parse(id);
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    // ====================== 玩家淘汰 (SRE 游戏死亡) ======================

    private static void eliminatePlayer(ServerPlayer target, ServerPlayer shooter) {
        try {
            var gameWorld = SREGameWorldComponent.KEY.get(target.serverLevel());
            if (gameWorld == null) return;

            gameWorld.removeRole(target);           // SRE 角色移除 = 游戏死亡
            gameWorld.addPlayerKill(shooter.getUUID()); // 记录击杀
            BlackoutRoleManager.eliminate(target.getUUID()); // 停电模式淘汰

            target.sendSystemMessage(Component.literal(
                "§c你被警长 §e" + shooter.getName().getString() + " §c击杀了！"));
            shooter.sendSystemMessage(Component.literal(
                "§a你击杀了 §e" + target.getName().getString()));

            LOGGER.info("{} eliminated {} via Desert Eagle (blackout mode)",
                shooter.getName().getString(), target.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to eliminate player {}", target.getName().getString(), e);
        }
    }

    // ====================== 清理 ======================

    public static void resetPurchases() {
        DESERT_EAGLE_PURCHASED.clear();
    }

    public static void reset() {
        DESERT_EAGLE_PURCHASED.clear();
        eventRegistered = false;
    }
}
