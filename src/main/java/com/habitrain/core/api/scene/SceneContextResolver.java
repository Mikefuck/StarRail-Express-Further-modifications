package com.habitrain.core.api.scene;

import net.minecraft.server.level.ServerLevel;

/**
 * 场景地图上下文解析接口（隔离具体游戏模组对地图名称与对局状态的实现）。
 */
public interface SceneContextResolver {
    record SceneContext(String mapKey, String dimensionKey, boolean matchActive) {}

    SceneContext resolve(ServerLevel level);

    /** Loading may precede the game's active-state flag. */
    default boolean isPreparingMatch(ServerLevel level) { return false; }
}
