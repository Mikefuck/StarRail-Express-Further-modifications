package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskCategory;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public class SREMurderMode extends SREGameModeBase {

    public static final String MODE_ID = "sre:murder";

    public SREMurderMode() {
        taskCategories.add(new TaskCategory("sre:murder", "谋杀模式", MODE_ID));
        taskCategories.add(TaskCategory.ALL);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return "经典列车谋杀案"; }

    @Override
    public List<TaskCategory> getTaskCategories() { return taskCategories; }

    @Override
    public boolean isActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            if (gw == null || gw.getGameMode() == null) return false;
            String modeId = gw.getGameMode().identifier.toString();
            return !modeId.contains("repair");
        } catch (Exception e) {
            return false;
        }
    }
}
