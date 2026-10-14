package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TicketClockTextTest {
    @Test
    void framesWithinAMinuteReuseLabelsAndMidnightUpdatesDateAndStamp() {
        TicketClockText clock = new TicketClockText(ZoneId.of("Asia/Shanghai"));
        long beforeMidnight = Instant.parse("2026-10-13T15:59:00Z").toEpochMilli();
        clock.update(beforeMidnight);
        String time = clock.time(), date = clock.date(), stamp = clock.stamp();
        assertEquals("23:59", time);
        assertEquals("2026.10.13", date);
        for (int frame = 0; frame < 3600; frame++) {
            clock.update(beforeMidnight + frame * 16);
            assertSame(time, clock.time());
            assertSame(date, clock.date());
            assertSame(stamp, clock.stamp());
        }
        clock.update(beforeMidnight + 60_000);
        assertEquals("00:00", clock.time());
        assertEquals("2026.10.14", clock.date());
        assertEquals("10.14", clock.stamp());
    }

    @Test
    void clockCorrectionsAreNotBlockedByTheCachedMinute() {
        TicketClockText clock = new TicketClockText(ZoneId.of("UTC"));
        long now = Instant.parse("2026-10-13T12:34:00Z").toEpochMilli();
        clock.update(now);
        String date = clock.date();
        clock.update(now + 60_000);
        assertEquals("12:35", clock.time());
        assertSame(date, clock.date());
        clock.update(now);
        assertEquals("12:34", clock.time());
        assertSame(date, clock.date());
    }
}
