package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap = false)
@Pseudo
public abstract class MinigameTaskAssignmentMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MinigameMapFilter");

    @Shadow(remap = false) @Mutable public String targetMinigameId;
    @Shadow(remap = false) @Mutable public int pendingMinigameTasks;

    @Shadow(remap = false) public abstract Player getPlayer();
    @Shadow(remap = false) public abstract void sync();

    @Inject(
            method = "dispatchMinigameTask",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void habitrain$weightedDispatchWithMapFilter(
            ServerPlayer sp, ServerLevel serverLevel, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (sp == null || serverLevel == null) {
                return;
            }
            AreasWorldComponent areas = AreasWorldComponent.KEY.get(serverLevel);
            if (areas == null || areas.availableMinigameIds == null || areas.availableMinigameIds.isEmpty()) {
                this.targetMinigameId = null;
                cir.setReturnValue(false);
                return;
            }
            String mapName = areas.mapName != null ? areas.mapName : "";
            String selected = habitrain$pickWeightedEnabledMinigame(areas.availableMinigameIds, mapName, sp);
            if (selected == null) {
                this.targetMinigameId = null;
                cir.setReturnValue(false);
                return;
            }
            this.pendingMinigameTasks++;
            this.targetMinigameId = selected;
            this.sync();
            cir.setReturnValue(true);
        } catch (Exception e) {
            LOGGER.warn("[MinigameMapFilter] 加权派发小游戏任务失败", e);
        }
    }

    /**
     * 在 serverTick 的 END 注入，兜底检查当前已指派的 targetMinigameId 在当前地图是否仍被允许（例如局中热改配置）。
     * 如果不允许，尝试按权重从可用集合中重抽一个；若整图无可用小游戏，则一并扣减 pendingMinigameTasks 并置空（防止进入任意任务点通配模式），最后 sync()。
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

            String selected = habitrain$pickWeightedEnabledMinigame(areas.availableMinigameIds, mapName, player);
            if (selected == null) {
                this.pendingMinigameTasks = Math.max(0, this.pendingMinigameTasks - 1);
                this.targetMinigameId = null;
            } else {
                this.targetMinigameId = selected;
            }
            this.sync();
        } catch (Exception e) {
            LOGGER.warn("[MinigameMapFilter] 地图过滤失败", e);
        }
    }

    @Unique
    private static String habitrain$pickWeightedEnabledMinigame(
            Iterable<String> availableIds, String mapName, Player player) {
        if (availableIds == null || player == null) {
            return null;
        }
        ConfigManager cfgMgr = ConfigManager.getInstance();
        List<String> candidates = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        float totalWeight = 0f;
        for (String id : availableIds) {
            if (id == null || id.isEmpty() || !cfgMgr.isMinigameEnabledForMap(id, mapName)) {
                continue;
            }
            MinigameConfigEntry entry = cfgMgr.getMinigameConfig(id);
            float w = entry != null ? entry.getRefreshWeight() : 1.0f;
            if (w <= 0f) {
                continue;
            }
            candidates.add(id);
            weights.add(w);
            totalWeight += w;
        }
        if (candidates.isEmpty() || totalWeight <= 0f) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        float roll = player.getRandom().nextFloat() * totalWeight;
        float acc = 0f;
        for (int i = 0; i < candidates.size(); i++) {
            acc += weights.get(i);
            if (roll <= acc) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
