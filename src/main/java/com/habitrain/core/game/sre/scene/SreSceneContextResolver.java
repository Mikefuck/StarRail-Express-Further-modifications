package com.habitrain.core.game.sre.scene;

import com.habitrain.core.game.sre.MapVoteLoadCoordinator;
import com.habitrain.core.scene.server.SceneContextResolver;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StarRailExpress 场景上下文适配器（API 内唯一允许读取 SRE 地图组件的类）。
 */
public final class SreSceneContextResolver implements SceneContextResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(SreSceneContextResolver.class.getSimpleName());

    public static final SreSceneContextResolver INSTANCE = new SreSceneContextResolver();

    private SreSceneContextResolver() {}

    @Override
    public SceneContext resolve(ServerLevel level) {
        if (level == null) {
            return new SceneContext("__default__", "minecraft:overworld", false);
        }

        String dim = level.dimension().location().toString();
        String votedMapKey = MapVoteLoadCoordinator.selectedMapId(level);
        String componentMapKey = null;
        boolean matchActive = false;

        try {
            var areas = io.wifi.starrailexpress.cca.AreasWorldComponent.KEY.get(level);
            if (areas != null && areas.mapName != null && !areas.mapName.isBlank()) {
                componentMapKey = areas.mapName;
            }
        } catch (Throwable t) {
            LOGGER.debug("读取 AreasWorldComponent 失败，使用默认 mapKey", t);
        }

        try {
            var gameWorld = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            if (gameWorld != null) {
                matchActive = gameWorld.isRunning();
            }
        } catch (Throwable t) {
            LOGGER.debug("读取 SREGameWorldComponent 失败", t);
        }

        return new SceneContext(resolveMapKey(votedMapKey, componentMapKey), dim, matchActive);
    }

    /** Vote result wins during the map-loading/start lifecycle; regular launches use SRE CCA. */
    static String resolveMapKey(String votedMapKey, String componentMapKey) {
        if (votedMapKey != null && !votedMapKey.isBlank()) return votedMapKey.trim();
        if (componentMapKey != null && !componentMapKey.isBlank()) return componentMapKey.trim();
        return "__default__";
    }
}
