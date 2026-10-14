package com.habitrain.core.client.gui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Wall-clock labels change on minute/day boundaries, independently of the render rate. */
final class TicketClockText {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.ROOT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM.dd", Locale.ROOT);
    private final ZoneId zone;
    private long minute = Long.MIN_VALUE;
    private LocalDate day;
    private String time = "", date = "", stamp = "";

    TicketClockText() {
        this(ZoneId.systemDefault());
    }

    TicketClockText(ZoneId zone) {
        this.zone = zone;
    }

    void update(long epochMillis) {
        long next = Math.floorDiv(epochMillis, 60_000L);
        if (next == minute) return;
        var now = Instant.ofEpochMilli(epochMillis).atZone(zone);
        time = TIME.format(now);
        LocalDate nextDay = now.toLocalDate();
        if (!nextDay.equals(day)) {
            date = DATE.format(now);
            stamp = STAMP.format(now);
            day = nextDay;
        }
        minute = next;
    }

    String time() { return time; }
    String date() { return date; }
    String stamp() { return stamp; }
}
