package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import io.wifi.starrailexpress.cca.SREWorldBlackoutComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mixin - 停电黑暗时长增强（可逆，默认关闭）。
 *
 * <p>SRE 原版 {@code SREWorldBlackoutComponent#playBlackoutSound()} 给无法无视停电的
 * 玩家固定施加 200 tick（10 秒）黑暗+失明，而普通商店停电的灯灭时长可达 25 秒
 * （SREConfig.blackoutMaxDuration），黑暗覆盖不全。
 *
 * <p>开关开启后：普通停电（灯灭 &gt; 200 tick，含商店 25 秒停电与 API Blackout 模式
 * 永久停电）的黑暗+失明延长到 400 tick（20 秒）；忍者商店「关灯」
 * （useBlackoutWithMultiplier 0.4，灯灭恰 200 tick）保持 200 tick（10 秒）。
 *
 * <p>注入点：{@code playBlackoutSound()} 内两个
 * {@code new MobEffectInstance(...)}（BLINDNESS ordinal=0、DARKNESS ordinal=1）的
 * duration 参数（index=1）。两个效果同长，分别修改。受众范围保持 SRE 原版过滤
 * （杀手/中立/可无视停电者不受影响），仅修改时长不改受众。
 *
 * <p>不使用 {@code remap=false}：目标类为 SRE mod 类，不在官方映射表中，
 * 默认重映射会原样保留类名与方法名；而 {@code @At} 的 Minecraft 类
 * （MobEffectInstance）会被正确重映射到运行时 intermediary 名称。
 */
@Mixin(SREWorldBlackoutComponent.class)
public abstract class SREBlackoutEffectEnhancementMixin {

    /** 本次停电总时长（tick）。playBlackoutSound() 调用时已等于本次停电时长。 */
    @Shadow
    public int blackOutRemainingTicks;

    /** 普通停电阈值：超过 200 tick 视为普通/永久停电；恰 200 tick 视为忍者关灯。 */
    private static final int NINJA_BLACKOUT_TICKS = 200;
    /** 普通停电增强后的黑暗+失明时长（20 秒）。 */
    private static final int NORMAL_BLACKOUT_EFFECT_TICKS = 400;
    /** 忍者关灯保持原版 10 秒黑暗。 */
    private static final int NINJA_BLACKOUT_EFFECT_TICKS = 200;

    @ModifyArg(
            method = "playBlackoutSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/effect/MobEffectInstance;<init>(Lnet/minecraft/core/Holder;IIZZZ)V",
                    ordinal = 0),
            index = 1)
    private int habitrain$enhanceBlindnessDuration(int duration) {
        return enhanceDuration(duration);
    }

    @ModifyArg(
            method = "playBlackoutSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/effect/MobEffectInstance;<init>(Lnet/minecraft/core/Holder;IIZZZ)V",
                    ordinal = 1),
            index = 1)
    private int habitrain$enhanceDarknessDuration(int duration) {
        return enhanceDuration(duration);
    }

    private int enhanceDuration(int original) {
        if (!ConfigManager.getInstance().isBlackoutEffectEnhancementEnabled()) {
            return original;
        }
        if (blackOutRemainingTicks > NINJA_BLACKOUT_TICKS) {
            return NORMAL_BLACKOUT_EFFECT_TICKS;
        }
        return NINJA_BLACKOUT_EFFECT_TICKS;
    }
}
