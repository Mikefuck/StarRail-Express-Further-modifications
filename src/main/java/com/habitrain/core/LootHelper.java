package com.habitrain.core;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class LootHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|LootHelper");

    private static final int ROLE_TYPE_KILLER = 4;
    private static final int ROLE_TYPE_SHERIFF = 5;
    private static final int NUNCHUCK_COOLDOWN_KILLER = 1000;
    private static final int NUNCHUCK_COOLDOWN_SHERIFF = 200;

    public static ItemStack giveRandomBackpackItem(ServerPlayer player) {
        return giveRandomBackpackItem(player, null);
    }

    /**
     * @param grantTaskId 若非空，在放入背包/掉落前给道具打回收标签，避免
     *                    Inventory.add() 消耗 stack 后再打标打到空栈上。
     */
    public static ItemStack giveRandomBackpackItem(ServerPlayer player, String grantTaskId) {
        try {
            var gameWorld = SREGameWorldComponent.KEY.get(player.level());
            var roles = gameWorld.getRoles();
            var role = roles.get(player.getUUID());
            if (role == null) {
                LOGGER.warn("玩家没有角色数据，无法发放背包奖励");
                return null;
            }

            int roleType = role.getRoleType();
            List<String> itemPool;

            if (roleType == ROLE_TYPE_KILLER) {
                itemPool = List.of(
                    "trainmurdermystery:crowbar",
                    "trainmurdermystery:nunchuck",
                    "noellesroles:fake_revolver",
                    "noellesroles:fire_axe",
                    "noellesroles:bucket_of_h2so4",
                    "noellesroles:throwing_knife",
                    "noellesroles:boxing_glove",
                    "noellesroles:pan",
                    "noellesroles:handcuffs",
                    "noellesroles:rope",
                    "noellesroles:signed_paper",
                    "noellesroles:delivery_box",
                    "exposure_polaroid:instant_camera",
                    "noellesroles:extinguisher",
                    "trainmurdermystery:poison_vial"
                );
            } else if (roleType == ROLE_TYPE_SHERIFF) {
                itemPool = List.of(
                    "trainmurdermystery:lockpick",
                    "trainmurdermystery:firecracker",
                    "trainmurdermystery:iron_door_key",
                    "noellesroles:handcuffs"
                );
            } else {
                itemPool = List.of(
                    "betel-nut-mod:synthetic_world_betel",
                    "trainmurdermystery:emoji_helmet",
                    "trainmurdermystery:defense_vial",
                    "noellesroles:noell_paperclip",
                    "noellesroles:screwdriver"
                );
            }

            int idx = player.getRandom().nextInt(itemPool.size());
            String itemId = itemPool.get(idx);

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item, 1);
                if (grantTaskId != null && !grantTaskId.isBlank()) {
                    com.habitrain.core.api.ItemReclaimHelper.tagGrantedItem(stack, grantTaskId);
                }
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }

                if ("trainmurdermystery:nunchuck".equals(itemId)) {
                    int initialCooldown = (roleType == ROLE_TYPE_KILLER)
                            ? NUNCHUCK_COOLDOWN_KILLER
                            : NUNCHUCK_COOLDOWN_SHERIFF;
                    player.getCooldowns().addCooldown(item, initialCooldown);
                    LOGGER.debug("双节棍初始冷却: {} ticks ({}秒, roleType={})",
                            initialCooldown, initialCooldown / 20, roleType);
                }

                LOGGER.info("玩家 {} 翻找背包获得: {} (阵营类型: {})",
                    player.getName().getString(), itemId, roleType);
                return stack;
            } else {
                LOGGER.warn("找不到背包奖励物品: {}", itemId);
            }
        } catch (Exception e) {
            LOGGER.error("发放背包奖励时出错", e);
        }
        return null;
    }
}
