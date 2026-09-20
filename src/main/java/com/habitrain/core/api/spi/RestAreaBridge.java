package com.habitrain.core.api.spi;

import net.minecraft.server.level.ServerPlayer;

/**
 * 淘汰休息区状态桥接接口（SPI）。
 *
 * <p><b>为什么存在（审核 B-01）</b>：淘汰玩家进入「休息区」后，上游 SRE 仍把他当成
 * 已淘汰的旁观者，核心实现层因此维护了一份「谁正在休息」的表。下游（抽奖类）必须在
 * 抽奖 / 领邮件 / 用卡 / 名牌渲染等热路径上问同一件事，但此前公开层<b>没有</b>等价能力，
 * 于是下游只能越层 import 实现层的类——一旦核心重构或该类被移除，下游的
 * {@code catch (Throwable)} 会把 {@code NoClassDefFoundError} 吞掉，门禁静默失效。
 *
 * <p>本桥接把该能力收进公开层：{@code api} 只依赖本接口，实现由核心在 bootstrap 期装配
 * （见 {@link CoreSpi#installRestArea}），因此下游可以安全、可编译、可版本化地消费它。
 *
 * <h2>异常与未装配语义</h2>
 * <p>实现<b>可以自由抛出</b>——{@link CoreSpi#isPlayerResting} 统一
 * {@code try/catch(Throwable)}，<b>读取失败</b>时保守返回 {@code true}（判定为休息中），
 * 因为该查询的消费方向全部是拒绝型门禁：误判为「休息中」只是让门禁更严
 * （玩家看到「旁观、休息或死亡时不能…」），而误判为「没在休息」会让本应被拦住的
 * 玩家通过——这正是审核 B-01 / N-01 要消除的 fail-open 方向。
 *
 * <p><b>桥接尚未装配</b>（core 自己的 bootstrap 还没走到这一步）时
 * {@code CoreSpi.isPlayerResting} 返回 {@code false}：那是「引擎还没起来」而不是
 * 「检查失败」，此时把所有人都判成休息中会让名牌 / 旁观放行等语义在启动窗口内整体错乱。
 * 需要「core 能力缺失即收紧」的场景（例如服务端菜单门控）请<b>显式</b>读装配探针
 * {@link CoreSpi#isRestAreaInstalled()} / {@link CoreSpi#isMenuGateInstalled()}。
 *
 * <p>本接口的 {@code default} 实现是「玩家非 {@code null} 即视为休息中」，
 * 只在有人匿名 {@code new RestAreaBridge() {}} 或继承默认实现时生效；
 * 核心装配的 NOOP 被显式拒绝安装（见 {@code CoreSpi.install}），
 * 因此装配失败时字段保持 {@code null} 而不是这个宽松默认值。
 */
public interface RestAreaBridge {

    /**
     * 该玩家当前是否物理停留在淘汰休息区（不是「已淘汰」，而是「正在休息区里」）。
     *
     * @param player 目标玩家；{@code null} 时返回 {@code false}
     * @return {@code true} 表示必须按休息中处理
     */
    default boolean isResting(ServerPlayer player) {
        return player != null;
    }
}
