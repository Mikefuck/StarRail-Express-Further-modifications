package com.habitrain.core.game.blackout;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 常量透视方块类型 ID。
 * SRE 原版任务占用 typeId 0–12；自定义透视从 {@link #CUSTOM_OVERLAY_MIN_TYPE_ID}（13）起。
 * typeId 12 既不扫描也不绘制。STREET_PHONE = 90 避免与注册任务冲突。
 */
public final class BlackoutOverlayTypes {
    /** 内置自定义任务 / 透视的下限。scanner 与 renderer 必须用同一条界。 */
    public static final int CUSTOM_OVERLAY_MIN_TYPE_ID = 13;
    /** yuushya:street_phone 方块在透视缓存中的 typeId */
    public static final int STREET_PHONE = 90;
    /** trainmurdermystery:horn 方块在透视缓存中的 typeId */
    public static final int HORN = 91;
    /** decocraft:rotary_phone_red 方块在透视缓存中的 typeId（红色电话任务商店，仅停电模式常驻透视） */
    public static final int ROTARY_PHONE_RED = 92;

    private static Block cachedStreetPhone = null;
    private static Block cachedHorn = null;
    private static Block cachedRotaryPhoneRed = null;

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

    /** 获取 decocraft:rotary_phone_red 方块实例（缓存版） */
    public static Block getRotaryPhoneRedBlock() {
        if (cachedRotaryPhoneRed == null || cachedRotaryPhoneRed == Blocks.AIR) {
            cachedRotaryPhoneRed = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("decocraft:rotary_phone_red"));
        }
        return cachedRotaryPhoneRed;
    }

    private BlackoutOverlayTypes() {}
}
