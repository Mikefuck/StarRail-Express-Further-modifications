package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapResetProgressPercentTest {

    @Test
    void reportsTheSameRoundedPercentageAsTheUpstreamResetTask() {
        assertEquals(OptionalInt.of(42), MapResetProgressPercent.from(21, 50));
        assertEquals(OptionalInt.of(67), MapResetProgressPercent.from(2, 3));
    }

    @Test
    void clampsTransientOutOfRangeTaskValues() {
        assertEquals(OptionalInt.of(0), MapResetProgressPercent.from(-1, 10));
        assertEquals(OptionalInt.of(100), MapResetProgressPercent.from(11, 10));
    }

    @Test
    void rejectsTasksWithoutAUsableTotalSoTheCallerCanFallBack() {
        assertTrue(MapResetProgressPercent.from(0, 0).isEmpty());
        assertTrue(MapResetProgressPercent.from(5, -1).isEmpty());
    }
}
