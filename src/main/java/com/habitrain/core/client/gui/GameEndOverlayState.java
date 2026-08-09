package com.habitrain.core.client.gui;

import net.minecraft.Util;

/**
 * 对局结束转场覆盖层的全局可见状态。
 *
 * <p>供客户端 Mixin 查询：结束转场覆盖层激活（或刚关闭的宽限期内）时，屏蔽 SRE 原版的
 * 黑场淡入淡出，避免与我们的结算转场画面叠加。</p>
 *
 * <p>与 {@link VoteLaunchOverlayState} 独立而非共用：两者的生命周期不同——开局转场由
 * {@code ClientLifecycleHandler.resetState()} 统一清理，而结束转场必须在
 * {@code OnGameFinishedClient} 触发滑出的同一帧<b>不被</b> resetState() 误清
 * （滑出期间与交还后宽限期内仍需持续屏蔽 SRE fade）。</p>
 */
public final class GameEndOverlayState {
    private static boolean active = false;
    /** 覆盖层关闭后仍屏蔽原版动画的宽限期结束时间戳（毫秒）。 */
    private static long blockUntilMillis = Long.MIN_VALUE;

    private GameEndOverlayState() {}

    public static boolean isActive() {
        return active;
    }

    /** 覆盖层已关闭但仍在宽限期内（屏蔽 SRE 黑场）。 */
    public static boolean isBlockingNow() {
        return active || Util.getMillis() < blockUntilMillis;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /** 覆盖层关闭时调用：开启宽限期，期间仍屏蔽原版动画。 */
    public static void scheduleGrace(long graceMillis) {
        blockUntilMillis = Util.getMillis() + Math.max(0, graceMillis);
        active = false;
    }
}
