package com.habitrain.core.client.gui;

import net.minecraft.Util;

/**
 * 投票结束后开局转场覆盖层的全局可见状态。
 *
 * <p>供客户端 Mixin 查询：转场覆盖层激活（或刚关闭的宽限期内）时，屏蔽 SRE 原版的
 * 黑场淡入淡出与开局相机拉近动画，避免与我们的转场画面叠加。</p>
 */
public final class VoteLaunchOverlayState {
    private static boolean active = false;
    /** 覆盖层关闭后仍屏蔽原版动画的宽限期结束时间戳（毫秒）。 */
    private static long blockUntilMillis = Long.MIN_VALUE;
    /** 覆盖层关闭后仍阻止 SRE 场景环境循环音启动的短宽限期。 */
    private static long ambientSoundBlockUntilMillis = Long.MIN_VALUE;

    private VoteLaunchOverlayState() {}

    public static boolean isActive() {
        return active;
    }

    /** 覆盖层已关闭但仍在宽限期内（屏蔽开局相机/黑场）。 */
    public static boolean isBlockingNow() {
        return active || Util.getMillis() < blockUntilMillis;
    }

    /**
     * 开局覆盖层仍在，或客户端刚交还世界、传送位置与区块露天判定尚在稳定窗口内。
     */
    public static boolean isAmbientSoundBlockingNow() {
        return active || Util.getMillis() < ambientSoundBlockUntilMillis;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /** 覆盖层关闭时调用：开启宽限期，期间仍屏蔽原版开局动画。 */
    public static void scheduleGrace(long graceMillis) {
        blockUntilMillis = Util.getMillis() + Math.max(0, graceMillis);
        active = false;
    }

    /** 与较长的相机宽限期分离，避免正常的室内/室外环境音被无谓延迟数秒。 */
    public static void scheduleAmbientSoundGrace(long graceMillis) {
        ambientSoundBlockUntilMillis = Util.getMillis() + Math.max(0, graceMillis);
    }
}
