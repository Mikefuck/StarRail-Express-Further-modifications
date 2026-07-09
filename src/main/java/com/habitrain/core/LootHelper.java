package com.habitrain.core;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class LootHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|LootHelper");

    private static final int ROLE_TYPE_GOOD_POLICE = 4;
    private static final int ROLE_TYPE_BAD = 5;
    private static final int NUNCHUCK_COOLDOWN_GOOD_POLICE = 1000;
    private static final int NUNCHUCK_COOLDOWN_BAD = 200;

    public static ItemStack giveRandomBackpackItem(ServerPlayer player) {
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

            if (roleType == ROLE_TYPE_GOOD_POLICE) {
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
                    "noellesroles:extinguisher"
                );
            } else if (roleType == ROLE_TYPE_BAD) {
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
                    "trainmurdermystery:poison_vial",
                    "noellesroles:noell_paperclip",
                    "noellesroles:screwdriver"
                );
            }

            int idx = player.getRandom().nextInt(itemPool.size());
            String itemId = itemPool.get(idx);

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item, 1);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }

                if ("trainmurdermystery:nunchuck".equals(itemId)) {
                    int initialCooldown = (roleType == ROLE_TYPE_GOOD_POLICE) ? NUNCHUCK_COOLDOWN_GOOD_POLICE : NUNCHUCK_COOLDOWN_BAD;
                    player.getCooldowns().addCooldown(item, initialCooldown);
                    LOGGER.debug("双节棍初始冷却: {} ticks ({}秒, roleType={})",
                            initialCooldown, initialCooldown / 20, roleType);
                }

                player.displayClientMessage(
                    Component.literal("§e你从背包中翻找到了: ").append(stack.getHoverName()), true);
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
