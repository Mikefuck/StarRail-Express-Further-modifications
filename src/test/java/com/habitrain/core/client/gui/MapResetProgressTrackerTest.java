package com.habitrain.core.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapResetProgressTrackerTest {
    @Test
    void carriagesThenFixturesFormOneMonotonicBar() {
        MapResetProgressTracker tracker = new MapResetProgressTracker();
        tracker.observe(50, false, 1_000L);
        assertEquals(40, tracker.progress());
        assertEquals(MapResetProgressTracker.Stage.CARRIAGES, tracker.stage());

        tracker.observe(100, false, 1_500L);
        assertEquals(80, tracker.progress());

        // 复位机关阶段从 0 重新计数，进度条不能倒退
        tracker.observe(0, true, 4_000L);
        assertEquals(80, tracker.progress());
        assertEquals(MapResetProgressTracker.Stage.FIXTURES, tracker.stage());
        tracker.observe(50, true, 4_500L);
        assertEquals(90, tracker.progress());
    }

    @Test
    void fixturesOnlyResetUsesWholeBar() {
        MapResetProgressTracker tracker = new MapResetProgressTracker();
        tracker.observe(50, true, 1_000L);
        assertEquals(50, tracker.progress());
    }

    @Test
    void visibilityFollowsLastObservationAndFinish() {
        MapResetProgressTracker tracker = new MapResetProgressTracker();
        assertFalse(tracker.isVisible(0L));
        tracker.observe(10, false, 1_000L);
        assertTrue(tracker.isVisible(1_000L + MapResetProgressTracker.VISIBLE_AFTER_LAST_MILLIS - 1));
        assertFalse(tracker.isVisible(1_000L + MapResetProgressTracker.VISIBLE_AFTER_LAST_MILLIS));

        tracker.finish(2_000L);
        assertEquals(100, tracker.progress());
        assertEquals(MapResetProgressTracker.Stage.DEPARTING, tracker.stage());
        assertTrue(tracker.isVisible(2_000L + MapResetProgressTracker.FINISHED_HOLD_MILLIS - 1));
        assertFalse(tracker.isVisible(2_000L + MapResetProgressTracker.FINISHED_HOLD_MILLIS));
    }

    @Test
    void newRunStartsFromZero() {
        MapResetProgressTracker tracker = new MapResetProgressTracker();
        tracker.observe(100, true, 1_000L);
        tracker.finish(1_200L);
        tracker.observe(10, false, 60_000L);
        assertEquals(8, tracker.progress());
        assertEquals(MapResetProgressTracker.Stage.CARRIAGES, tracker.stage());

        tracker.observe(50, false, 61_000L);
        tracker.observe(5, false, 61_000L + MapResetProgressTracker.NEW_RUN_GAP_MILLIS + 1);
        assertEquals(4, tracker.progress());
    }

    @Test
    void upstreamResettingActionBarIsSuppressedOthersPass() {
        MapResetProgressHud.reset();
        assertFalse(MapResetProgressHud.allowOverlayMessage(
                Component.translatable("message.sre.reseting", "42").withStyle(ChatFormatting.YELLOW)));
        assertFalse(MapResetProgressHud.allowOverlayMessage(
                Component.translatable("message.sre.reseting", "oops").withStyle(ChatFormatting.GOLD)));
        assertTrue(MapResetProgressHud.allowOverlayMessage(
                Component.translatable("message.sre.starting").withStyle(ChatFormatting.GREEN)));
        assertTrue(MapResetProgressHud.allowOverlayMessage(Component.literal("hello")));
        MapResetProgressHud.reset();
    }
}
