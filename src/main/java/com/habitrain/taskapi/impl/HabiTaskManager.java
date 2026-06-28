package com.habitrain.taskapi.impl;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskInstance;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务管理器 - 运行时任务生成和管理的核心
 */
public class HabiTaskManager {
    private static HabiTaskManager INSTANCE;

    public static HabiTaskManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new HabiTaskManager();
        }
        return INSTANCE;
    }

    // 自定义任务跟踪表 - 不依赖SRE的CUSTOM枚举
    private final Map<UUID, HabiTaskInstance> activeCustomTasks = new HashMap<>();

    /**
     * 获取玩家的活跃自定义任务
     */
    public HabiTaskInstance getActiveCustomTask(UUID playerUuid) {
        return activeCustomTasks.get(playerUuid);
    }

    /**
     * 设置玩家的活跃自定义任务
     */
    public void setActiveCustomTask(UUID playerUuid, HabiTaskInstance task) {
        activeCustomTasks.put(playerUuid, task);
    }

    /**
     * 移除玩家的活跃自定义任务
     */
    public void removeActiveCustomTask(UUID playerUuid) {
        activeCustomTasks.remove(playerUuid);
    }

    /**
     * 检查玩家是否已有某个ID的自定义任务 (不依赖CUSTOM枚举)
     */
    public boolean hasCustomTaskWithId(UUID playerUuid, String fullId) {
        HabiTaskInstance existing = activeCustomTasks.get(playerUuid);
        boolean has = existing != null && existing.getFullId().equals(fullId);
        if (has) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] hasCustomTaskWithId: player={} already has task {}", playerUuid, fullId);
        }
        return has;
    }

    /**
     * 获取当前地图名称
     */
    public String getCurrentMapName(Player player) {
        if (player == null || player.level() == null) return "";
        try {
            AreasWorldComponent areas = AreasWorldComponent.KEY.get(player.level());
            String name = areas != null && areas.mapName != null ? areas.mapName : "";
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getCurrentMapName='{}' for player {}", name, player.getName().getString());
            return name;
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[HabiDebug] getCurrentMapName failed for player " + player.getName().getString(), e);
            return "";
        }
    }

    /**
     * 获取当前游戏模式分类
     */
    public HabiTaskCategory getCurrentGameModeCategory(Player player) {
        if (player == null || player.level() == null) return HabiTaskCategory.ALL;
        try {
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
            if (gameWorld == null || gameWorld.getGameMode() == null) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getCurrentGameModeCategory: gameWorld={}, gameMode=null -> ALL",
                    (gameWorld != null ? "present" : "null"));
                return HabiTaskCategory.ALL;
            }

            String modeId = gameWorld.getGameMode().identifier.toString();
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getCurrentGameModeCategory: modeId='{}'", modeId);

            // 修机模式
            if (modeId.contains("repair_escape") || modeId.contains("repair")) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getCurrentGameModeCategory -> REPAIR");
                return HabiTaskCategory.REPAIR;
            }
            // 默认为谋杀模式
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getCurrentGameModeCategory -> MURDER");
            return HabiTaskCategory.MURDER;
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[HabiDebug] getCurrentGameModeCategory failed for player " +
                (player != null ? player.getName().getString() : "null"), e);
            return HabiTaskCategory.ALL;
        }
    }

    /**
     * 获取当前地图可用的任务列表 (基于配置过滤)
     */
    public List<HabiTaskDefinition> getAvailableTasks(String mapName, HabiTaskCategory currentCategory) {
        List<HabiTaskDefinition> available = new ArrayList<>();
        HabiConfigManager config = HabiConfigManager.getInstance();

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getAvailableTasks: mapName='{}', category={}, total tasks in registry={}",
            mapName, currentCategory, HabiTaskRegistry.size());

        for (HabiTaskDefinition def : HabiTaskRegistry.getAll()) {
            HabiTaskConfigEntry entry = config.getTaskConfig(def.getFullId());

            // 检查任务是否启用(针对当前地图)
            boolean mapEnabled = isTaskEnabledForMap(entry, mapName);
            if (!mapEnabled) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   SKIP {}: disabled for map '{}' (entry={})",
                    def.getFullId(), mapName, (entry != null ? "enabled=" + entry.enabled : "null"));
                continue;
            }

            // 检查分类匹配
            boolean categoryMatch = (def.getCategory() == HabiTaskCategory.ALL
                || def.getCategory() == HabiTaskCategory.CUSTOM
                || def.getCategory() == currentCategory);
            if (!categoryMatch) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   SKIP {}: category {} != current {}",
                    def.getFullId(), def.getCategory(), currentCategory);
                continue;
            }

            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   ADD {}: modId={}, category={}, weight={}",
                def.getFullId(), def.getModId(), def.getCategory(), def.getWeight());
            available.add(def);
        }

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getAvailableTasks result: {} tasks available", available.size());
        return available;
    }

    /**
     * 检查任务在指定地图上是否启用
     *
     * ★ 地图过滤规则（v3）：
     * - disabled = 全局禁用（不看 mapFilterMode）
     * - mapFilterMode=0（不启用）: 所有地图启用
     * - mapFilterMode=1（白名单）: 仅 enabledMaps 中的地图启用，空列表=全局
     * - mapFilterMode=2（黑名单）: 排除 enabledMaps 中的地图，空列表=全局
     */
    private boolean isTaskEnabledForMap(HabiTaskConfigEntry entry, String mapName) {
        if (entry == null) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   isTaskEnabledForMap: entry=null -> true");
            return true;
        }

        if (!entry.enabled) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   isTaskEnabledForMap: disabled -> false");
            return false;
        }

        // mapFilterMode: 0=不启用, 1=白名单, 2=黑名单
        if (entry.mapFilterMode == 0) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   isTaskEnabledForMap: no filter -> true (global)");
            return true;
        }

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) { // 白名单
            boolean result = listEmpty || contained;
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   isTaskEnabledForMap: whitelist={}, mapName='{}', result={}",
                entry.enabledMaps, mapName, result);
            return result;
        } else { // 黑名单 (mapFilterMode == 2)
            boolean result = listEmpty || !contained;
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   isTaskEnabledForMap: blacklist={}, mapName='{}', result={}",
                entry.enabledMaps, mapName, result);
            return result;
        }
    }

    /**
     * 从注册表中生成一个随机任务
     */
    public HabiTaskInstance generateTask(Player player, SREPlayerTaskComponent originalComponent) {
        String mapName = getCurrentMapName(player);
        HabiTaskCategory category = getCurrentGameModeCategory(player);
        List<HabiTaskDefinition> available = getAvailableTasks(mapName, category);

        if (available.isEmpty()) return null;

        // 过滤掉已存在的任务
        List<HabiTaskDefinition> filtered = available.stream()
                .filter(def -> !hasCustomTaskWithId(originalComponent, def.getFullId()))
                .filter(def -> def.canAssign(player, null))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) return null;

        // 权重随机选择
        float totalWeight = 0;
        for (HabiTaskDefinition def : filtered) {
            totalWeight += def.getWeight();
        }

        if (totalWeight <= 0) return null;

        float random = player.getRandom().nextFloat() * totalWeight;
        for (HabiTaskDefinition def : filtered) {
            random -= def.getWeight();
            if (random <= 0) {
                HabiTaskInstance instance = new HabiTaskInstance(def);
                def.onAssign(player, instance);
                return instance;
            }
        }

        // fallback
        HabiTaskDefinition last = filtered.get(filtered.size() - 1);
        HabiTaskInstance instance = new HabiTaskInstance(last);
        last.onAssign(player, instance);
        return instance;
    }

    /**
     * 检查玩家是否已有某个自定义任务
     */
    private boolean hasCustomTaskWithId(SREPlayerTaskComponent component, String fullId) {
        if (component == null || component.tasks == null) return false;
        SREPlayerTaskComponent.Task customEnum = TaskEnumHelper.getCustom();
        if (customEnum == null) return false;
        var task = component.tasks.get(customEnum);
        if (task instanceof HabiTaskInstance hti) {
            return hti.getFullId().equals(fullId);
        }
        return false;
    }

    /**
     * 判断原版任务是否被配置禁用
     */
    public boolean isOriginalTaskDisabled(String taskName, String mapName) {
        String fullId = "habitrain_taskapi:" + taskName.toLowerCase();
        HabiTaskConfigEntry entry = HabiConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] isOriginalTaskDisabled {}: no config -> false (enabled)", fullId);
            return false;
        }
        boolean disabled = !isTaskEnabledForMap(entry, mapName);
        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] isOriginalTaskDisabled {}: entry.enabled={}, mapName='{}' -> {}",
            fullId, entry.enabled, mapName, disabled);
        return disabled;
    }

    /**
     * 处理任务完成后的额外逻辑
     * 注意: DLC模组的 onComplete 回调已在 HabiTaskInstance.tick() 中调用
     * 此方法仅处理框架级别的逻辑 (如直接获胜)
     *
     * ★ 修复：不在此处发放金币/情绪奖励。
     * 配置奖励（goldReward / emotionReward）由 RoleMethodDispatcherMixin 统一处理，
     * 它在原版 SRE 的 callOnFinishQuest 流程中被调用。
     * 如果此处也发放奖励，会导致与 RoleMethodDispatcherMixin 重复发放。
     */
    public void handleTaskCompletion(ServerPlayer player, HabiTaskInstance instance) {
        HabiTaskDefinition def = instance.getDefinition();

        // 如果任务配置为可直接获胜
        if (def.canDirectlyWin()) {
            // 触发直接获胜逻辑
            triggerDirectWin(player, instance);
        }
    }

    /**
     * 触发直接获胜 - 完成某些任务可直接获得游戏胜利
     */
    private void triggerDirectWin(ServerPlayer player, HabiTaskInstance instance) {
        try {
            // 使用原版API触发自定义获胜
            // 通过RoleMethodDispatcher / GameUtils 的现有接口实现
            io.wifi.starrailexpress.cca.SREGameRoundEndComponent roundEnd =
                    io.wifi.starrailexpress.cca.SREGameRoundEndComponent.KEY.get(player.level());
            if (roundEnd != null) {
                roundEnd.CustomWinnerID = instance.getDefinition().getModId() + "_" + instance.getDefinition().getTaskId() + "_win";
                roundEnd.CustomWinnerPlayers.add(player.getUUID());
                roundEnd.setWinStatus(io.wifi.starrailexpress.game.GameUtils.WinStatus.CUSTOM);
                roundEnd.sync();
            }
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("Failed to trigger direct win for task: " + instance.getFullId(), e);
        }
    }
}
