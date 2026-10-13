package com.habitrain.core.vote;

import org.junit.jupiter.api.Test;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.ModeMapVoteConfig;
import com.habitrain.core.config.ModeMapVoteSettings;
import net.minecraft.server.level.ServerLevel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeMapVoteOrchestratorTest {

    @Test
    void repairModeIdsMatchRegistryAndUpstreamEscapeIds() {
        assertTrue(ModeMapVoteOrchestrator.isRepairModeId("habitrain_core:sre:repair"));
        assertTrue(ModeMapVoteOrchestrator.isRepairModeId("sre:repair"));
        assertTrue(ModeMapVoteOrchestrator.isRepairModeId("sre:repair_escape"));
        assertTrue(ModeMapVoteOrchestrator.isRepairModeId("wifi:repair_escape"));
    }

    @Test
    void murderAndCustomModesKeepMapVote() {
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("habitrain_core:sre:murder"));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("example:custom"));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("wifi:tnt_tag"));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId(null));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId(""));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("repair_kit"));
    }

    @Test
    void repairLaunchMapIdIsTheAutoManorTokenNotATrainMap() {
        assertEquals("repair_manor", ModeMapVoteOrchestrator.REPAIR_LAUNCH_MAP_ID);
        assertFalse(MapVoteProfileStore.isReservedMapId(ModeMapVoteOrchestrator.REPAIR_LAUNCH_MAP_ID));
    }

    @Test
    void configuredUnknownModesNeverBecomeCandidatesAndFiltersStillApply() {
        String id = "vote_candidate_test:registered";
        if (GameModeRegistry.get(id) == null) {
            GameModeRegistry.register("vote_candidate_test", "registered", new GameMode() {
                public String getId() { return id; }
                public String getDisplayName() { return "test"; }
                public List<TaskCategory> getTaskCategories() { return List.of(); }
                public boolean isActive(ServerLevel level) { return false; }
            });
        }
        ModeMapVoteSettings settings = new ModeMapVoteSettings();
        assertFalse(ModeMapVoteOrchestrator.isModeAllowed(null, settings, "removed_dlc:mode"));
        assertTrue(ModeMapVoteOrchestrator.isModeAllowed(null, settings, id));
        ModeMapVoteConfig config = new ModeMapVoteConfig();
        config.modeIds = List.of("removed_dlc:mode");
        assertFalse(ModeMapVoteOrchestrator.isModeAllowed(config, settings, id));
        assertFalse(ModeMapVoteOrchestrator.isModeAllowed(config, settings, "removed_dlc:mode"));
        config.modeIds = List.of(id);
        assertTrue(ModeMapVoteOrchestrator.isModeAllowed(config, settings, id));
    }
}
