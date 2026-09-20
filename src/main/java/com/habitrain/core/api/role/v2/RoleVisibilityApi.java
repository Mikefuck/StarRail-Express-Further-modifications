package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.spi.CoreSpi;
import io.wifi.starrailexpress.api.SRERole;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 角色可见性与解析的门面（审核 M-01 / B-01）。
 *
 * <p>核心的角色覆盖机制会把被隐藏（hide）或被替换（replace）的基线角色从
 * 「角色目录 / 角色书 / 新建分配池 / 候选列表」中剔除。凡是<b>自行持有角色列表</b>并从中
 * 挑选候选的玩法逻辑（例如旋转选角的候选生成）都必须用同一套语义过滤，否则本应不可见的
 * 职业会重新出现在候选列表里。
 *
 * <p>{@link RoleCatalogApi} 提供的是「按 key 查目录」的接口，但不适用于「我手上已经有一个
 * {@link SRERole} 对象，它现在还算数吗」这种问题——本类补上这一块，且不要求调用方
 * 构造 {@link RoleKey}。
 *
 * <h2>降级方向</h2>
 * <p>本类是<b>过滤型</b>能力，不是安全门禁：桥接未装配时
 * {@link #isVisible(SRERole)} 返回 {@code true}、{@link #resolve(SRERole)} 原样返回，
 * 即「没有过滤信息就不过滤」。这样未装配只会让候选列表退化为未过滤，
 * 而不会变成空列表把选角玩法整个打断。详见
 * {@link com.habitrain.core.api.spi.RoleVisibilityBridge}。
 */
public final class RoleVisibilityApi {

    private RoleVisibilityApi() {}

    /**
     * 把手上持有的角色对象解析成当前快照下的有效角色
     * （跟随 ALIAS 重定向、REPLACE 替换与 MODIFY 覆盖）。
     *
     * @return 解析结果；入参为 {@code null} 时返回 {@code null}
     */
    public static @Nullable SRERole resolve(@Nullable SRERole role) {
        if (role == null) {
            return null;
        }
        return CoreSpi.resolveRoleForVisibility(role);
    }

    /**
     * 该角色是否应当出现在新建的分配池 / 候选列表 / 角色书里。
     * 被隐藏或被替换的角色返回 {@code false}。{@code null} 恒为 {@code false}。
     */
    public static boolean isVisible(@Nullable SRERole role) {
        if (role == null) {
            return false;
        }
        return CoreSpi.isRoleVisible(role);
    }

    /**
     * 过滤一份角色集合：剔除被隐藏 / 被替换 / 无法解析的角色，并把其余角色替换为
     * 当前快照下的有效对象，保持原有顺序。
     *
     * @param roles 源集合；{@code null} 或空时返回空列表
     * @return 新的可变列表（调用方可安全地再过滤 / 打乱）
     */
    public static List<SRERole> filterVisible(@Nullable Collection<SRERole> roles) {
        List<SRERole> out = new ArrayList<>();
        if (roles == null || roles.isEmpty()) {
            return out;
        }
        for (SRERole role : roles) {
            if (role == null || role.identifier() == null) {
                continue;
            }
            SRERole resolved = resolve(role);
            if (resolved == null || resolved.identifier() == null) {
                continue;
            }
            if (!isVisible(resolved)) {
                continue;
            }
            out.add(resolved);
        }
        return out;
    }

    /** 可见性桥接是否已装配（诊断 / 能力判定用）。 */
    public static boolean isAvailable() {
        return CoreSpi.isRoleVisibilityInstalled();
    }
}
