package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.game.AbstractGameMode;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;

/**
 * Thin Core {@link com.habitrain.core.api.GameMode} proxy for an original SRE mode.
 * <p>
 * Exists so the mode appears in {@link com.habitrain.core.api.GameModeRegistry} (and thus
 * mode-map vote). Actual match logic lives entirely in SRE; this proxy only mirrors
 * identity / active state and does not own lifecycle or tasks.
 * <p>
 * Must not extend {@link SREGameModeBase} — that base registers shared voice/task hooks
 * once per construction path and is reserved for murder/repair-style Core wrappers.
 */
public final class SreOriginalModeProxy extends AbstractGameMode {

    private final ResourceLocation sreId;
    private final List<TaskCategory> taskCategories;

    public SreOriginalModeProxy(ResourceLocation sreId) {
        this.sreId = Objects.requireNonNull(sreId, "sreId");
        this.taskCategories = List.of(TaskCategory.ALL);
    }

    /** Original SRE mode id, e.g. {@code wifi:tnt_tag}. */
    public ResourceLocation getSreId() {
        return sreId;
    }

    @Override
    public String getId() {
        return sreId.toString();
    }

    @Override
    public String getDisplayName() {
        // Mike: use raw SRE ids for vote labels when no operator override is set.
        return sreId.toString();
    }

    @Override
    public List<TaskCategory> getTaskCategories() {
        return taskCategories;
    }

    @Override
    public boolean isActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            if (gw == null || gw.getGameMode() == null || gw.getGameMode().identifier == null) {
                return false;
            }
            return sreId.equals(gw.getGameMode().identifier);
        } catch (Exception e) {
            return false;
        }
    }
}
