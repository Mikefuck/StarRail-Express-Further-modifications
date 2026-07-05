package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap = false)
public abstract class MinigameTaskAssignmentMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MinigameMapFilter");

    @Shadow(remap = false) public String targetMinigameId;

    @Shadow(remap = false) public abstract Player getPlayer();

    /**
     * 在 serverTick 的 END 注入，检查刚被选中的 targetMinigameId 在当前地图是否允许。
     * 如果不允许，尝试从可用集合中找到一个被允许的，否则置空（跳过本轮分配）。
     */
    @Inject(
            method = "serverTick",
            at = @At("RETURN"),
            remap = false
    )
    private void habitrain$enforceMapFilter(CallbackInfo ci) {
        try {
            if (targetMinigameId == null || targetMinigameId.isEmpty()) return;
            Player player = getPlayer();
            if (player == null || player.level() == null) return;

            AreasWorldComponent areas = AreasWorldComponent.KEY.get(player.level());
            if (areas == null) return;
            String mapName = areas.mapName != null ? areas.mapName : "";

            if (ConfigManager.getInstance().isMinigameEnabledForMap(targetMinigameId, mapName)) return;

            // 当前选中的小游戏在当前地图被禁用 — 从可用集合中找一个允许的
            List<String> candidates = new ArrayList<>();
            for (String id : areas.availableMinigameIds) {
                if (ConfigManager.getInstance().isMinigameEnabledForMap(id, mapName)) {
                    candidates.add(id);
                }
            }
            if (candidates.isEmpty()) {
                targetMinigameId = null;
            } else {
                targetMinigameId = candidates.get(player.getRandom().nextInt(candidates.size()));
            }
        } catch (Throwable t) {
            LOGGER.warn("[MinigameMapFilter] 地图过滤失败", t);
        }
    }
}