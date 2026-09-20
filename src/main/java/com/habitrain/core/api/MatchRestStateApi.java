package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreSpi;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 淘汰休息区状态查询（公开门面）。
 *
 * <p><b>审核 B-01</b>：淘汰玩家进入休息区后，抽奖 / 邮件 / 用卡 / 名牌渲染等热路径都必须
 * 按「休息中」拒绝或降级处理。该判定此前<b>只有实现层可用</b>，下游只能越层 import，
 * 并用 {@code catch (Throwable)} 掩盖 {@code NoClassDefFoundError}——核心一旦重构，
 * 门禁就静默 fail-open（旁观/休息玩家可以抽奖、领邮件、用卡）。
 *
 * <p>本类把该能力搬进公开层：调用方只依赖 {@link com.habitrain.core.api.spi.RestAreaBridge}
 * 与 {@link CoreSpi}，不再出现任何实现层类型；核心重构时下游会得到<b>编译期</b>错误
 * 而不是运行期的静默降级。
 *
 * <h2>未装配 / 读取失败语义</h2>
 * <p>两种情况<b>刻意区分</b>：
 * <ul>
 *   <li><b>读取失败</b>（桥接实现抛异常）：{@link #isResting} 保守返回 {@code true}
 *       （判定为休息中）。该查询的消费方向全部是拒绝型门禁，误判为「休息中」只是更严。</li>
 *   <li><b>桥接尚未装配</b>（core 引擎还没 bootstrap 完）：返回 {@code false}，
 *       因为把所有人都判成休息中会让名牌 / 旁观放行等语义在启动窗口内整体错乱。</li>
 * </ul>
 * <p>需要「core 能力缺失即收紧」的场景，请显式读 {@link #isAvailable()}
 * （装配<b>失败</b>时如实返回 {@code false}），而不是依赖 {@link #isResting} 的默认方向。
 *
 * <h2>下游兼容探针</h2>
 * <p>本类随 core 2.0.12 引入。绑定更老的核心（≤ 2.0.11）时直接引用会
 * {@code NoClassDefFoundError}，下游应在启动期先探测：
 * <pre>{@code
 * boolean hasRestApi;
 * try {
 *     Class.forName("com.habitrain.core.api.MatchRestStateApi", false,
 *             MatchRestStateApi.class.getClassLoader());
 *     hasRestApi = true;
 * } catch (Throwable missing) {
 *     hasRestApi = false; // 老核心：必须自行 fail-closed
 * }
 * }</pre>
 */
public final class MatchRestStateApi {

    private MatchRestStateApi() {}

    /**
     * 该玩家当前是否物理停留在淘汰休息区。
     *
     * <p>返回 {@code true} 时调用方必须按「旁观、休息或死亡」处理：不得抽奖、不得领取邮件、
     * 不得使用卡牌。
     *
     * @param player 目标玩家；{@code null} 返回 {@code false}
     */
    public static boolean isResting(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return CoreSpi.isPlayerResting(player);
    }

    /** 休息区桥接是否已装配（诊断 / 能力判定用）。 */
    public static boolean isAvailable() {
        return CoreSpi.isRestAreaInstalled();
    }
}
