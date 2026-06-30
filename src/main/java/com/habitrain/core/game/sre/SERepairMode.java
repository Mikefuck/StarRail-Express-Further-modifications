package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskCategory;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public class SRERepairMode extends SREGameModeBase {

    public static final String MODE_ID = "sre:repair";

    public SRERepairMode() {
        taskCategories.add(new TaskCategory("sre:repair", "修机模式", MODE_ID));
        taskCategories.add(TaskCategory.ALL);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return "修复逃脱模式"; }

    @Override
    public List<TaskCategory> getTaskCategories() { return taskCategories; }

    @Override
    public boolean isActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            if (gw == null || gw.getGameMode() == null) return false;
            String modeId = gw.getGameMode().identifier.toString();
            return modeId.contains("repair");
        } catch (Exception e) {
            return false;
        }
    }
}
