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

    @Test void soleChosenPlayerOnlyReceivesHalfAndSoleUnchosenPlayerIsCapped() {
        assertEquals(Map.of(A, 120), LustPolicy.distribute(24, List.of(A), A));
        assertEquals(Map.of(A, 150), LustPolicy.distribute(24, List.of(A), null));
        assertEquals(Map.of(A, 150), LustPolicy.distribute(31, List.of(A), A));
    }

    @Test void ordinarySharesAreCappedAt150IncludingRemainderCoins() {
        assertEquals(Map.of(A, 145, B, 145), LustPolicy.distribute(29, List.of(A, B), null));
        assertEquals(Map.of(A, 150, B, 150), LustPolicy.distribute(30, List.of(A, B), null));
        assertEquals(Map.of(A, 150, B, 150), LustPolicy.distribute(31, List.of(A, B), null));
        assertEquals(Map.of(A, 150, B, 150, C, 150), LustPolicy.distribute(46, List.of(A, B, C), null));
    }

    @Test void chosenAndOtherSharesAreCappedWithoutRedistributingExcess() {
        assertEquals(Map.of(B, 150, A, 100, C, 100), LustPolicy.distribute(40, List.of(A, B, C), B));
        assertEquals(Map.of(B, 150, A, 150), LustPolicy.distribute(40, List.of(A, B), B));
    }

    @Test void deadChosenPlayerDoesNotReserveAnyCoins() {
        assertEquals(Map.of(A, 120, B, 120), LustPolicy.distribute(24, List.of(A, B), C));
        assertTrue(LustPolicy.distribute(24, List.of(), C).isEmpty());
    }

    @Test void repeatedPlayersCannotBePaidTwice() {
        assertEquals(Map.of(A, 120, B, 120), LustPolicy.distribute(24, List.of(A, A, B), null));
    }

    @Test void giftTotalsRespectEachSharesCapAcrossDifferentRoundAndRecipientSizes() {
        for (int opening = 24; opening < 64; opening++) {
            for (int count = 1; count <= opening; count++) {
                List<UUID> recipients = new ArrayList<>();
                for (int n = 0; n < count; n++) recipients.add(new UUID(0, n));
                int total = LustPolicy.distribute(opening, recipients, null).values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(Math.min(opening * 10, count * 150), total);
                total = LustPolicy.distribute(opening, recipients, recipients.getFirst()).values().stream().mapToInt(Integer::intValue).sum();
                int expected = Math.min(opening * 5, 150)
                        + Math.min(opening * 5, (count - 1) * 150);
                assertEquals(expected, total);
            }
        }
    }

    @Test void revivalAllowsExactlyTenSecondsButRejectsExpiredAndFutureDeaths() {
        assertTrue(LustPolicy.canRevive(1000, 1000));
        assertTrue(LustPolicy.canRevive(1000, 1200));
        assertFalse(LustPolicy.canRevive(1000, 1201));
        assertFalse(LustPolicy.canRevive(1000, 999));
    }

    @Test void followingNeedsLustAndAnActuallyWinningCharmedPlayerToSurvive() {
        assertTrue(LustPolicy.canFollow(true, Set.of(A, B), Set.of(B, C), Set.of(B)));
        assertFalse(LustPolicy.canFollow(false, Set.of(A, B), Set.of(B, C), Set.of(B)));
        assertFalse(LustPolicy.canFollow(true, Set.of(A), Set.of(B, C), Set.of(A, B, C)));
        assertFalse(LustPolicy.canFollow(true, Set.of(), Set.of(A), Set.of(A)));
        assertFalse(LustPolicy.canFollow(true, Set.of(A), Set.of(A), Set.of()));
    }

    @Test void livingCharmedLoserAndDeadCharmedWinnerDoNotQualifyTogether() {
        assertFalse(LustPolicy.canFollow(true, Set.of(A, B), Set.of(B), Set.of(A)));
        assertFalse(LustPolicy.canFollow(true, Set.of(A), Set.of(A, B), Set.of(B)));
    }
}
