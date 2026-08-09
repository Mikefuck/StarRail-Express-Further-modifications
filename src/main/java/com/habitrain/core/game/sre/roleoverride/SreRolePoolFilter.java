package com.habitrain.core.game.sre.roleoverride;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import net.exmo.sre.repair.role.RepairRole;
import org.agmas.harpymodloader.Harpymodloader;
import org.agmas.harpymodloader.SREDisableManager;

import java.util.List;

/**
 * 当前模式（谋杀/停电）随机转职池的统一准入过滤器。
 * <p>
 * 对齐上游谋杀模式建池过滤（{@code RoleRotationWorldComponent} /
 * {@code SREMurderGameMode#getAllRoles} / {@code RoleAssignmentPool}），
 * 所有「随机把玩家变成另一个角色」的池（替罪羊转杀手、Mike 代码修改、
 * 暴怒转职、停电雇警等）都必须走这里，避免把其他模式角色、修机模式角色、
 * 已被上游禁用的角色、或被标记不可被随机到的角色抽进池里。
 */
public final class SreRolePoolFilter {
    private SreRolePoolFilter() {}

    /**
     * 修机/逃脱模式角色标记（{@link RepairRole} 构造器写入）。作为
     * {@code instanceof RepairRole} 之外的防御性兜底，防止修复后上游改类。
     */
    public static final String REPAIR_GAMEMODE_FLAG = "inner.repair_gamemode";

    /**
     * 该角色是否允许进入当前模式的随机转职池。
     * <p>排除项：空角色、其他模式角色、修机模式角色、原版占位角色
     * （平民/杀手/警长/亡命徒/发现平民）、上游禁用角色、以及
     * {@code setCanBeRandomedByOtherRoles(false)} 标记的角色。
     */
    public static boolean isCurrentModeRandomizable(SRERole role) {
        if (role == null || role.identifier() == null) return false;
        try {
            if (role.isOtherModeRole()) return false;
        } catch (Throwable t) {
            // fail-closed：检查失败时保守排除，防止其他模式角色漏进池
            HabiTrainCore.LOGGER.warn("[RolePoolFilter] isOtherModeRole check failed, role excluded", t);
            return false;
        }
        if (role instanceof RepairRole || role.isFlag(REPAIR_GAMEMODE_FLAG)) return false;
        try {
            if (Harpymodloader.VANNILA_ROLES.contains(role)) return false;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[RolePoolFilter] vanilla-role check failed, role excluded", t);
            return false;
        }
        try {
            if (SREDisableManager.isRoleDisabled(role)) return false;
        } catch (Throwable t) {
            // fail-closed：禁用 API 异常时保守排除，防止已禁用角色被抽中。
            // 空池由各调用方安全处理（Mike/替罪羊/暴怒/停电雇警均有空池分支）。
            HabiTrainCore.LOGGER.warn("[RolePoolFilter] isRoleDisabled check failed, role excluded", t);
            return false;
        }
        try {
            if (!role.canBeRandomedDefination()) return false;
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[RolePoolFilter] canBeRandomed check failed, role excluded", t);
            return false;
        }
        return true;
    }

    /**
     * 防御性回归检测：池中出现不应出现的角色时打警告日志。
     * 所有随机转职池在返回前都应调用一次。
     */
    public static void warnIfLeaky(String poolName, List<SRERole> pool) {
        if (pool == null) return;
        for (SRERole role : pool) {
            if (role != null && !isCurrentModeRandomizable(role)) {
                HabiTrainCore.LOGGER.warn(
                        "[RolePoolFilter] pool '{}' contains non-randomizable role {}",
                        poolName, role.identifier());
            }
        }
    }
}
