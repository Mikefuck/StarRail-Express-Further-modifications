package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;

import java.util.Set;

/**
 * 停电任务 ID 单一来源：专属（商店/强制）、供电池轮换、日常轮换。
 */
public final class BlackoutExclusiveTasks {

    public static final String TASK_FURNACE_EXPLOSION = HabiTrainCore.MOD_ID + ":furnace_explosion";
    public static final String TASK_SABOTAGE_WIRING = HabiTrainCore.MOD_ID + ":sabotage_wiring";
    public static final String TASK_RESTORE_POWER = HabiTrainCore.MOD_ID + ":restore_power";

    /** 商店购买 / 强制恢复等「专属」任务（禁止与普通左上角任务并行等）。 */
    public static final Set<String> IDS = Set.of(
            HabiTrainCore.TASK_REPAIR_WIRING,
            HabiTrainCore.TASK_MAINTAIN_POWER,
            HabiTrainCore.TASK_ADD_COAL,
            TASK_FURNACE_EXPLOSION,
            TASK_SABOTAGE_WIRING,
            TASK_RESTORE_POWER
    );

    /** 好人供电池轮换池。 */
    public static final Set<String> SUPPLY_TASK_IDS = Set.of(
            HabiTrainCore.TASK_ADD_COAL,
            HabiTrainCore.TASK_REPAIR_WIRING,
            HabiTrainCore.TASK_MAINTAIN_POWER
    );

    /** 好人日常轮换池。 */
    public static final Set<String> DAILY_TASK_IDS = Set.of(
            HabiTrainCore.TASK_BLACKOUT_EAT,
            HabiTrainCore.TASK_BLACKOUT_DRINK,
            HabiTrainCore.TASK_BLACKOUT_SEARCH_BACKPACK,
            HabiTrainCore.TASK_BLACKOUT_BETEL_QUEST,
            HabiTrainCore.TASK_BLACKOUT_PET_CAT,
            HabiTrainCore.TASK_BLACKOUT_BE_ALONE,
            HabiTrainCore.TASK_BLACKOUT_LOOK_MY_EYES
    );

    private BlackoutExclusiveTasks() {}

    public static boolean isExclusive(String fullId) {
        return fullId != null && IDS.contains(fullId);
    }

    public static boolean isSupply(String fullId) {
        return fullId != null && SUPPLY_TASK_IDS.contains(fullId);
    }

    public static boolean isDaily(String fullId) {
        return fullId != null && DAILY_TASK_IDS.contains(fullId);
    }
}
