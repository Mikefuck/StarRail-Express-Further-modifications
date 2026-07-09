package com.habitrain.core.game.blackout;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 常量透视方块类型 ID。
 * blockTypeId < 12 是 SRE 原版保留，≥12 是自定义，12 本身被跳过。
 * STREET_PHONE = 90 确保不与任何注册任务冲突。
 */
public final class BlackoutOverlayTypes {
    /** yuushya:street_phone 方块在透视缓存中的 typeId */
    public static final int STREET_PHONE = 90;
    /** trainmurdermystery:horn 方块在透视缓存中的 typeId */
    public static final int HORN = 91;

    private static Block cachedStreetPhone = null;
    private static Block cachedHorn = null;

    /** 获取 yuushya:street_phone 方块实例（缓存版） */
    public static Block getStreetPhoneBlock() {
        if (cachedStreetPhone == null || cachedStreetPhone == Blocks.AIR) {
            cachedStreetPhone = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("yuushya:street_phone"));
        }
        return cachedStreetPhone;
    }

    /** 获取 trainmurdermystery:horn 方块实例（缓存版） */
    public static Block getHornBlock() {
        if (cachedHorn == null || cachedHorn == Blocks.AIR) {
            cachedHorn = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("trainmurdermystery:horn"));
        }
        return cachedHorn;
    }

    private BlackoutOverlayTypes() {}
}
