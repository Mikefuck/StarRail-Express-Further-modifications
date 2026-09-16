package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存预算的记账与淘汰选取契约。
 *
 * <p>报告的 §6.2 修复点：解码缓存此前没有任何字节记账与上限。这里固定的是"记账不会漂移"
 * 以及"在用资产永不入选淘汰"。</p>
 */
class SceneMemoryBudgetTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void putRemoveAndClearKeepTheRunningTotalHonest() {
        SceneMemoryBudget budget = new SceneMemoryBudget(100 * MIB);

        budget.put("a", 10 * MIB);
        budget.put("b", 20 * MIB);
        assertEquals(30 * MIB, budget.usedBytes());
        assertEquals(2, budget.size());

        budget.remove("a");
        assertEquals(20 * MIB, budget.usedBytes());

        budget.clear();
        assertEquals(0L, budget.usedBytes());
        assertEquals(0, budget.size());
    }

    @Test
    void overwritingAKeyDoesNotDoubleCount() {
        SceneMemoryBudget budget = new SceneMemoryBudget(100 * MIB);

        budget.put("a", 10 * MIB);
        budget.put("a", 25 * MIB);

        assertEquals(25 * MIB, budget.usedBytes(), "覆盖写必须先减旧值再记新值");
        assertEquals(1, budget.size());
    }

    @Test
    void evictionPicksTheLeastRecentlyUsedFirst() {
        SceneMemoryBudget budget = new SceneMemoryBudget(50 * MIB);
        budget.put("old", 20 * MIB);
        budget.put("middle", 20 * MIB);
        budget.put("new", 20 * MIB);
        budget.put("hot", 20 * MIB);
        budget.touch("old"); // 命中一次，让它不再是"最久未用"

        List<String> victims = budget.selectEvictions(Set.of());

        assertEquals(List.of("middle", "new"), victims,
                "应淘汰到刚好进入配额内，且按最久未用升序");
    }

    @Test
    void protectedKeysAreNeverSelectedEvenWhenThatLeavesItOverQuota() {
        SceneMemoryBudget budget = new SceneMemoryBudget(10 * MIB);
        budget.put("in-use", 40 * MIB);
        budget.put("spare", 5 * MIB);

        List<String> victims = budget.selectEvictions(Set.of("in-use"));

        assertEquals(List.of("spare"), victims);
        assertTrue(budget.isOverQuota(), "选了 spare 仍然超标，这是允许的");
        assertEquals(1, budget.overQuotaEvents(), "无解的情况要能被诊断看到");
    }

    @Test
    void loweringTheQuotaTakesEffectImmediately() {
        SceneMemoryBudget budget = new SceneMemoryBudget(500 * MIB);
        budget.put("a", 100 * MIB);
        assertFalse(budget.isOverQuota());
        assertTrue(budget.selectEvictions(Set.of()).isEmpty());

        budget.setQuotaBytes(50 * MIB);

        assertTrue(budget.isOverQuota());
        assertEquals(List.of("a"), budget.selectEvictions(Set.of()));
    }

    @Test
    void raisingTheQuotaSelectsNothing() {
        SceneMemoryBudget budget = new SceneMemoryBudget(50 * MIB);
        budget.put("a", 40 * MIB);

        budget.setQuotaBytes(500 * MIB);

        assertFalse(budget.isOverQuota());
        assertTrue(budget.selectEvictions(Set.of()).isEmpty(), "放宽配额不应触发任何淘汰");
    }

    @Test
    void touchingAnUnknownKeyIsANoOp() {
        SceneMemoryBudget budget = new SceneMemoryBudget(50 * MIB);

        budget.touch("ghost");

        assertEquals(0L, budget.usedBytes());
        assertFalse(budget.contains("ghost"));
    }

    @Test
    void evictedBytesAreReportedForDiagnostics() {
        SceneMemoryBudget budget = new SceneMemoryBudget(50 * MIB);

        budget.recordEvicted(12 * MIB);
        budget.recordEvicted(-5L);

        assertEquals(12 * MIB, budget.evictedTotalBytes());
    }
}
