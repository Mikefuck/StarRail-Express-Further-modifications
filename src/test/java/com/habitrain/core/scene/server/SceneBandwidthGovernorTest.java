package com.habitrain.core.scene.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 带宽预约器的排队契约。
 *
 * <p>这批断言固定的是「限速等待不再占用 IO 线程」的前提：同一玩家单调不减（延迟投递的
 * 有序性依据），以及跨玩家只共享全局预算、不共享单玩家预算。</p>
 */
class SceneBandwidthGovernorTest {
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final long MIB = 1024L * 1024L;
    private static final long GLOBAL = 16L * MIB;
    private static final long SLOW_PLAYER = 1L * MIB;

    private static final class FakeClock {
        long nowNanos;

        long current() {
            return nowNanos;
        }
    }

    private static SceneBandwidthGovernor governor(FakeClock clock) {
        return new SceneBandwidthGovernor(clock::current, GLOBAL);
    }

    /** 65536 字节在 1 MiB/s 下需要 62.5 ms。 */
    private static final long CHUNK_NANOS_AT_1MIB = 65_536L * 1_000_000_000L / SLOW_PLAYER;

    @Test
    void samePlayerReservationsAreMonotonicallyNonDecreasing() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        long first = governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        long second = governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        long third = governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);

        assertTrue(second >= first, "同一玩家的 readyAt 必须单调不减，否则延迟投递会乱序");
        assertTrue(third >= second);
        assertEquals(CHUNK_NANOS_AT_1MIB, second - first, 1L);
        assertEquals(CHUNK_NANOS_AT_1MIB, third - second, 1L);
    }

    @Test
    void anotherPlayersSlowReservationDoesNotSerialiseBehindItsOwnBudget() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        long aReadyAt = governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        long bReadyAt = governor.reserve(PLAYER_B, 65536, SLOW_PLAYER);

        assertEquals(0L, aReadyAt, "第一个请求应立即到期");
        // B 只被全局预算推后（64 KiB / 16 MiB/s ≈ 3.9 ms），绝不能被 A 的单玩家预算推后 62.5 ms。
        assertTrue(bReadyAt < CHUNK_NANOS_AT_1MIB / 4,
                "B 的等待应只反映全局预算，实际 " + bReadyAt + " ns");
        assertTrue(bReadyAt > 0L, "全局预算仍应串行化两个玩家，否则全局限速形同虚设");
    }

    @Test
    void aPlayersOwnCursorDoesNotDelayAnotherPlayer() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        // A 先预约一大段慢速配额，把自己的游标推到很远。
        governor.reserve(PLAYER_A, 65536 * 8, SLOW_PLAYER);
        clock.nowNanos = CHUNK_NANOS_AT_1MIB * 4;

        long bReadyAt = governor.reserve(PLAYER_B, 65536, SLOW_PLAYER);
        long expectedGlobalFloor = GLOBAL > 0
                ? CHUNK_NANOS_AT_1MIB * 8 * SLOW_PLAYER / GLOBAL
                : 0L;

        assertTrue(bReadyAt <= clock.nowNanos + expectedGlobalFloor + 1L,
                "B 不该被 A 的单玩家游标拖住，实际 " + bReadyAt + " ns");
    }

    @Test
    void globalBudgetBoundsTheSumAcrossPlayers() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        long secondReadyAt = governor.reserve(PLAYER_B, 65536, SLOW_PLAYER);

        long oneChunkGlobalNanos = 65_536L * 1_000_000_000L / GLOBAL;
        assertEquals(oneChunkGlobalNanos, secondReadyAt, 1L,
                "第二个玩家的到期时刻应正好是全局游标");
        assertEquals(2L * oneChunkGlobalNanos, governor.nextGlobalSendNanos(), 1L,
                "两个玩家合计必须受全局限速约束");
    }

    @Test
    void forgetReleasesOnlyThatPlayersCursor() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        governor.reserve(PLAYER_B, 65536, SLOW_PLAYER);
        assertEquals(2, governor.trackedPlayers());

        governor.forget(PLAYER_A);
        assertEquals(1, governor.trackedPlayers());

        // 全局游标仍然生效，因此重新预约不是立刻到期——只丢单玩家游标，不丢全局记账。
        long globalCursor = governor.nextGlobalSendNanos();
        long reReserved = governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        assertEquals(globalCursor, reReserved);
    }

    @Test
    void resetClearsPlayersAndGlobalCursor() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        governor.reserve(PLAYER_A, 65536, SLOW_PLAYER);
        governor.reset();

        assertEquals(0, governor.trackedPlayers());
        assertEquals(0L, governor.reserve(PLAYER_B, 65536, SLOW_PLAYER));
    }

    @Test
    void zeroByteReservationDoesNotAdvanceTheCursor() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        long first = governor.reserve(PLAYER_A, 0, SLOW_PLAYER);
        long second = governor.reserve(PLAYER_A, 0, SLOW_PLAYER);
        assertEquals(first, second, "零字节预约不应消耗配额");
    }

    @Test
    void nullPlayerStillAdvancesTheGlobalCursor() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        long first = governor.reserve(null, 65536, SLOW_PLAYER);
        long second = governor.reserve(null, 65536, SLOW_PLAYER);

        assertEquals(GLOBAL > 0 ? 65536L * 1_000_000_000L / GLOBAL : 0L, second - first, 1L);
    }

    @Test
    void nonPositiveRatesFallBackToImmediate() {
        FakeClock clock = new FakeClock();
        SceneBandwidthGovernor governor = governor(clock);

        // 速率被非法传入为 0/负数时不得除零或产生负延迟。
        long readyAt = governor.reserve(PLAYER_A, 65536, 0L);
        assertTrue(readyAt >= 0L);
        assertTrue(governor.nextGlobalSendNanos() > 0L);
    }
}
