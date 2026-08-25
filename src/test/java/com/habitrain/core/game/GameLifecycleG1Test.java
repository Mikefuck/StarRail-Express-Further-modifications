package com.habitrain.core.game;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutExileVoteManager;
import com.habitrain.core.game.sre.EliminatedRestAreaService;
import com.habitrain.core.game.sre.MapVoteLoadCoordinator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Smoke tests for G1 lifecycle guards that do not require a launched world.
 */
class GameLifecycleG1Test {

    @Test
    void mapVoteLoadCoordinatorResetAllIsIdempotent() {
        assertDoesNotThrow(MapVoteLoadCoordinator::resetAll);
        assertDoesNotThrow(() -> MapVoteLoadCoordinator.reset(null));
        assertFalse(MapVoteLoadCoordinator.isLoading(null));
        assertDoesNotThrow(MapVoteLoadCoordinator::resetAll);
    }

    @Test
    void restoreCandidateNoopsWithoutLevel() {
        assertFalse(BlackoutExileVoteManager.isVoteActive(null));
        assertDoesNotThrow(() ->
                BlackoutExileVoteManager.restoreCandidate(null, UUID.randomUUID()));
    }

    @Test
    void markEliminatedAcceptsNullAndUuid() {
        assertDoesNotThrow(() -> EliminatedRestAreaService.markEliminated(null));
        assertDoesNotThrow(() -> EliminatedRestAreaService.markEliminated(UUID.randomUUID()));
    }

    @Test
    void gameModeTickAllAndUnauthorizedFreezeAreNoops() {
        assertFalse(GameModeRegistry.isFrozen());
        assertFalse(TaskRegistry.isFrozen());
        assertDoesNotThrow(() -> GameModeRegistry.tickAll(null));
        assertDoesNotThrow(GameModeRegistry::freeze);
        assertDoesNotThrow(TaskRegistry::freeze);
        assertFalse(GameModeRegistry.isFrozen());
        assertFalse(TaskRegistry.isFrozen());
        assertFalse(GameModeRegistry.isActiveInLevel(null));
    }
}
