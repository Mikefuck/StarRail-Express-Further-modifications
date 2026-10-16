package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LustPolicyTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);

    @Test void ordinaryGiftUsesOpeningCountAndConservesRemainders() {
        var grants = LustPolicy.distribute(25, List.of(A, B, C), null);
        assertEquals(250, grants.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Set.of(83, 84), new HashSet<>(grants.values()));
    }

    @Test void chosenPlayerGetsHalfAndOthersShareTheRest() {
        var grants = LustPolicy.distribute(25, List.of(A, B, C, D), B);
        assertEquals(125, grants.get(B));
        assertEquals(125, grants.entrySet().stream().filter(e -> !e.getKey().equals(B)).mapToInt(Map.Entry::getValue).sum());
        assertEquals(1, Math.abs(grants.get(A) - grants.get(D)));
    }

    @Test void soleChosenPlayerOnlyReceivesHalfButUnchosenPlayerReceivesAll() {
        assertEquals(Map.of(A, 120), LustPolicy.distribute(24, List.of(A), A));
        assertEquals(Map.of(A, 240), LustPolicy.distribute(24, List.of(A), null));
    }

    @Test void deadChosenPlayerDoesNotReserveAnyCoins() {
        assertEquals(Map.of(A, 120, B, 120), LustPolicy.distribute(24, List.of(A, B), C));
        assertTrue(LustPolicy.distribute(24, List.of(), C).isEmpty());
    }

    @Test void repeatedPlayersCannotBePaidTwice() {
        assertEquals(Map.of(A, 120, B, 120), LustPolicy.distribute(24, List.of(A, A, B), null));
    }

    @Test void giftConservationAcrossDifferentRoundAndRecipientSizes() {
        for (int opening = 24; opening < 64; opening++) {
            for (int count = 1; count <= opening; count++) {
                List<UUID> recipients = new ArrayList<>();
                for (int n = 0; n < count; n++) recipients.add(new UUID(0, n));
                int total = LustPolicy.distribute(opening, recipients, null).values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(opening * 10, total);
                total = LustPolicy.distribute(opening, recipients, recipients.getFirst()).values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(count == 1 ? opening * 5 : opening * 10, total);
            }
        }
    }

    @Test void revivalAllowsExactlyTenSecondsButRejectsExpiredAndFutureDeaths() {
        assertTrue(LustPolicy.canRevive(1000, 1000));
        assertTrue(LustPolicy.canRevive(1000, 1200));
        assertFalse(LustPolicy.canRevive(1000, 1201));
        assertFalse(LustPolicy.canRevive(1000, 999));
    }

    @Test void followingNeedsBothSurvivalAndAnActuallyWinningCharmedPlayer() {
        assertTrue(LustPolicy.canFollow(true, Set.of(A, B), Set.of(B, C)));
        assertFalse(LustPolicy.canFollow(false, Set.of(A, B), Set.of(B, C)));
        assertFalse(LustPolicy.canFollow(true, Set.of(A), Set.of(B, C)));
        assertFalse(LustPolicy.canFollow(true, Set.of(), Set.of(A)));
    }
}
