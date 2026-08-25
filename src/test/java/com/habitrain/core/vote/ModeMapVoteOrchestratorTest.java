package com.habitrain.core.vote;

import org.junit.jupiter.api.Test;

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
    void murderAndBlackoutKeepMapVote() {
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("habitrain_core:sre:murder"));
        assertFalse(ModeMapVoteOrchestrator.isRepairModeId("habitrain_core:habitrain:blackout"));
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
}
