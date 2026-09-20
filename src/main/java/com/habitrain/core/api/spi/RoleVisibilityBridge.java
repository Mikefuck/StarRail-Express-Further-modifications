package com.habitrain.core.api.spi;

import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

/**
 * 角色「解析 + 可见性」判定的公开桥接接口（SPI）。
 *
 * <p><b>为什么存在（审核 M-01 / B-01）</b>：核心会按当前角色覆盖快照过滤掉被隐藏
 * （hide）或被替换（replace）的基线角色。凡是「自己从角色池里挑候选」的下游
 * （例如旋转选角的候选生成）都必须用同一套语义过滤，否则被隐藏的职业会重新出现在
 * 候选列表里，表现为「随机出现不该出现的职业」。
 *
 * <p>此前该能力只在实现层可用，下游只能复制实现或越层 import。本桥接把它收进公开层，
 * 与 {@link RestAreaBridge} 一样由核心在 bootstrap 期装配（见
 * {@link CoreSpi#installRoleVisibility}）。
 *
 * <h2>方向约定（安全属性）</h2>
 * <p>与「拒绝型」门禁桥接不同，本桥接是<b>过滤型</b>能力：默认实现
 * （{@link #isVisible(SRERole)} 返回 {@code true}、{@link #resolve(SRERole)} 原样返回）
 * 表示「没有可用过滤信息，因此不做过滤」。这样未装配时下游的候选列表只会退化为
 * 「未过滤」，而<b>不会</b>变成空列表导致选角玩法整体不可用；需要 fail-closed 的是
 * 抽奖 / 邮件这类安全门禁，不是候选枚举。
 */
public interface RoleVisibilityBridge {

    /**
     * 把一份「手上持有的」角色对象解析成当前快照下的有效角色
     * （跟随 ALIAS 重定向、REPLACE 替换与 MODIFY 覆盖）。
     *
     * @return 解析结果；无法解析时实现应返回原对象或 {@code null}，由调用方决定降级方向
     */
    default @Nullable SRERole resolve(@Nullable SRERole role) {
        return role;
    }

    /**
     * 该角色是否应当出现在「新建的分配池 / 候选列表 / 角色书」里。
     *
     * @return {@code false} 表示被隐藏或被替换，必须从候选池中剔除
     */
    default boolean isVisible(@Nullable SRERole role) {
        return role != null;
    }
}
