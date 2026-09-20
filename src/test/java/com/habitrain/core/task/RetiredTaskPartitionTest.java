package com.habitrain.core.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import com.habitrain.core.network.ActiveTaskPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务子系统「已退役分区」守卫（audit P0-2 / P1-5）。
 *
 * <p>锁死两件事，防止它们被无声加回来：
 * <ol>
 *   <li><b>P0-2 幽灵分区</b>：杀手双任务（假任务）、强制分类、跳过 active 守卫。
 *       这四条分区当时恒为常量（{@code false}/{@code null}），但类型、参数、状态、
 *       网络字段与渲染分支全部健在；现已连同机制一次性删除，任何一处回流都应让本测试失败。</li>
 *   <li><b>P1-5 重做的原版任务</b>：Core 自建的 eat/drink 任务与消耗品分类器。
 *       上游 SRE 已经完整实现并派发 EAT/DRINK（含透视 type 1/2 与颜色覆盖），
 *       本 mod 只保留 {@code habitrain_core:eat|drink} 的<b>配置镜像</b>。</li>
 * </ol>
 */
class RetiredTaskPartitionTest {

    private static final String CLASS_ROOT = "/com/habitrain/core/";

    @Test
    void ghostPartitionClassesAreGone() {
        for (String retired : new String[] {
                "game/sre/FactionFilter",
                "game/sre/CoreConsumableTasks",
                "game/sre/CoreConsumableTaskPolicy",
                "game/sre/ConsumableClassificationPolicy",
                "game/sre/FoodDrinkConsumableClassifier",
                "game/sre/mixin/CoreEatMixin",
                "game/sre/mixin/CoreCanEatMixin",
                "game/sre/mixin/CoreDrinkItemMixin",
                "internal/CoreBootstrap"}) {
            assertNull(RetiredTaskPartitionTest.class.getResource(CLASS_ROOT + retired + ".class"),
                    retired + " must stay removed");
        }
    }

    @Test
    void taskManagerHasNoFakeTaskSurface() {
        assertFalse(anyMemberNameContains(TaskManager.class, "fake"),
                "TaskManager must not expose any fake-task member");
        // 取消路径只剩单一签名（旧签名带 boolean fake 参数）。
        List<Method> cancels = Arrays.stream(TaskManager.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("cancelTrackedTask"))
                .toList();
        assertEquals(1, cancels.size(), "cancelTrackedTask must have exactly one overload");
        assertEquals(2, cancels.get(0).getParameterCount());
    }

    @Test
    void activeTaskPayloadHasNoFakeFlag() {
        assertFalse(anyMemberNameContains(ActiveTaskPayload.class, "fake"),
                "ActiveTaskPayload must not carry an isFake flag anymore");
        assertFalse(Arrays.stream(ActiveTaskPayload.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("clearForPlayer") && m.getParameterCount() > 1),
                "ActiveTaskPayload.clearForPlayer must have no fake-slot overload");
    }

    @Test
    void trainTaskWrapperHasASingleSlotConstructor() {
        assertEquals(1, SRETrainTaskWrapper.class.getDeclaredConstructors().length,
                "SRETrainTaskWrapper must not keep the PRAY-slot (fake task) constructor");
        assertTrue(Arrays.stream(SRETrainTaskWrapper.class.getDeclaredConstructors())
                        .allMatch(c -> c.getParameterCount() == 1
                                && c.getParameterTypes()[0] == TaskInstance.class),
                "the only wrapper constructor must take a TaskInstance");
    }

    @Test
    void dlcTaskPoolBuilderHasNoGhostPartitionParameters() {
        List<Method> adders = Arrays.stream(com.habitrain.core.game.sre.DlcTaskPoolBuilder.class
                        .getDeclaredMethods())
                .filter(m -> m.getName().equals("addDlcTasks"))
                .toList();
        assertEquals(1, adders.size());
        assertEquals(8, adders.get(0).getParameterCount(),
                "addDlcTasks must not carry forcedCategory / skipActiveTaskGuard / currentIsFakeTask");
    }

    @Test
    void taskPoolBuilderHasNoForcedCategoryParameter() {
        assertTrue(Arrays.stream(TaskPoolBuilder.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("getPool"))
                        .allMatch(m -> m.getParameterCount() == 5),
                "TaskPoolBuilder.getPool must not take a forcedCategory override");
        assertTrue(Arrays.stream(TaskPoolBuilder.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("selectCandidates"))
                        .allMatch(m -> m.getParameterCount() == 5),
                "TaskPoolBuilder.selectCandidates must not take a forcedCategory override");
    }

    private static boolean anyMemberNameContains(Class<?> type, String needle) {
        String lower = needle.toLowerCase(java.util.Locale.ROOT);
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().toLowerCase(java.util.Locale.ROOT).contains(lower)) return true;
        }
        for (Field f : type.getDeclaredFields()) {
            if (f.getName().toLowerCase(java.util.Locale.ROOT).contains(lower)) return true;
        }
        return false;
    }
}
