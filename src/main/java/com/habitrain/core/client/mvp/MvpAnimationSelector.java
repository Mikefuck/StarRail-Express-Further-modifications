package com.habitrain.core.client.mvp;

import com.habitrain.core.config.MvpAnimationSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MVP 结算动画筛选与选择器（纯业务逻辑，无客户端图形依赖，便于单元测试）。
 */
public final class MvpAnimationSelector {

    private MvpAnimationSelector() {
    }

    /**
     * 为指定玩家与场次选取适合的 MVP 结算动画。
     *
     * @param settings     当前 MVP 动画配置
     * @param playerUuid   玩家 UUID
     * @param rankIndex    玩家在 MVP 列表中的位次（0 为单人或小队首席）
     * @param totalPlayers MVP 玩家总数
     * @param roundSeed    用于随机判定的稳定对局种子（如 startedAtMillis）
     * @param isSquad      是否为小队 MVP 展示模式
     * @return 选中的动画定义；若总开关关闭、全禁用或无可用动作则返回 null
     */
    public static MvpAnimationDefinition select(
            MvpAnimationSettings settings,
            UUID playerUuid,
            int rankIndex,
            int totalPlayers,
            long roundSeed,
            boolean isSquad
    ) {
        if (settings == null || !settings.enabled) {
            return null;
        }

        List<MvpAnimationDefinition> candidates = new ArrayList<>();
        for (MvpAnimationDefinition def : MvpAnimationDefinition.BUILT_INS) {
            if (!settings.isAnimationEnabled(def.id())) {
                continue;
            }
            if (isSquad && !def.squadSafe()) {
                continue;
            }
            candidates.add(def);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        if (!settings.randomSelection) {
            return candidates.get(0);
        }

        int count = candidates.size();
        if (settings.avoidDuplicates && count >= totalPlayers && totalPlayers > 1) {
            int baseOffset = Math.floorMod((int) (roundSeed ^ 0x5DEECE66DL), count);
            int idx = Math.floorMod(baseOffset + rankIndex, count);
            return candidates.get(idx);
        }

        long lsb = playerUuid != null ? playerUuid.getLeastSignificantBits() : 0L;
        long msb = playerUuid != null ? playerUuid.getMostSignificantBits() : 0L;
        int hash = Math.floorMod(Objects.hash(roundSeed, lsb, msb, rankIndex), count);
        return candidates.get(hash);
    }
}
