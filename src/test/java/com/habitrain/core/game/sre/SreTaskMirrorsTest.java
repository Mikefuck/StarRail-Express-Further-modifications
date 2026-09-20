package com.habitrain.core.game.sre;


import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版任务镜像表守卫（audit P1-5 根治项）。
 *
 * <p>{@link SreTaskMirrors} 是「哪些任务是原版任务」的单一真相：注册、派发池排除、
 * 配置页归组与计数全部从它派生。本测试锁死表本身的不变量、它与派发池判据的一致性，
 * 以及「另外两个使用点确实是从它派生的」，防止再次出现「三套清单内容各不相同」。</p>
 */
class SreTaskMirrorsTest {

    @Test
    void mirrorIdsAreUniqueAndNonEmpty() {
        List<String> ids = SreTaskMirrors.all().stream().map(SreTaskMirrors::id).toList();
        assertEquals(ids.size(), SreTaskMirrors.ids().size(), "duplicate mirror id detected");
        assertFalse(ids.isEmpty());
        assertTrue(new HashSet<>(ids).containsAll(List.of(
                "sleep", "eat", "drink", "repair_wire", "light_stove", "harvest_crop")),
                "the mirror table must cover the tasks that used to live in three separate lists");
    }

    @Test
    void blockTypeIdsStayInsideTheUpstreamTypeRangeOrMinusOne() {
        for (SreTaskMirrors mirror : SreTaskMirrors.all()) {
            int type = mirror.blockTypeId();
            assertTrue(type == -1 || (type >= 1 && type <= SreTaskMirrors.MAX_UPSTREAM_TYPE_ID),
                    mirror.id() + " has out-of-range blockTypeId " + type);
        }
    }

    /**
     * 镜像只允许携带元数据。任何「扫描方式 / 完成判定 / onTick」字段都会把镜像变回
     * Core 重做的原版任务，因此把记录的组件集合也锁死。
     */
    @Test
    void mirrorRecordCarriesMetadataOnly() {
        var components = SreTaskMirrors.class.getRecordComponents();
        assertEquals(5, components.length, "mirror record must carry metadata only");
        Set<String> names = new HashSet<>();
        for (var component : components) {
            names.add(component.getName());
        }
        assertEquals(Set.of("id", "displayName", "category", "weight", "blockTypeId"), names);
    }

    /**
     * 派发池排除清单（{@code GenerateTaskMixin}）与配置页归组清单（{@code ModeTasksPage}）
     * 都必须<b>派生</b>自本表，而不是各自维护一份字面量。
     *
     * <p>用常量池扫描而不是反射：{@code ModeTasksPage} 是客户端类，测试运行时不应加载它。
     * 池判据本身在 {@code TaskPoolBuilderIsolationTest}（同包才能访问包内方法）。
     */
    @Test
    void bothConsumerListsDeriveFromTheMirrorTable() {
        assertReferencesMirrorTable("com/habitrain/core/game/sre/mixin/GenerateTaskMixin.class");
        assertReferencesMirrorTable("com/habitrain/core/client/gui/menu/page/ModeTasksPage.class");
    }

    private static void assertReferencesMirrorTable(String resourcePath) {
        String pool;
        try (InputStream in = SreTaskMirrorsTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(in, "missing compiled class: " + resourcePath);
            pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            throw new AssertionError("cannot read " + resourcePath, e);
        }
        assertTrue(pool.contains("com/habitrain/core/game/sre/SreTaskMirrors"),
                resourcePath + " must derive its original-task list from SreTaskMirrors");
    }

    /** 使用点不应再留下字面量清单（抽查若干原版任务 id 是否仍以字符串常量出现在这些类里）。 */
    @Test
    void consumersNoLongerHardcodeTheVanillaTaskIdList() {
        for (String resourcePath : new String[] {
                "com/habitrain/core/game/sre/mixin/GenerateTaskMixin.class",
                "com/habitrain/core/client/gui/menu/page/ModeTasksPage.class"}) {
            String pool;
            try (InputStream in = SreTaskMirrorsTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
                assertNotNull(in, "missing compiled class: " + resourcePath);
                pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (Exception e) {
                throw new AssertionError("cannot read " + resourcePath, e);
            }
            assertFalse(pool.contains("harvest_crop"),
                    resourcePath + " still hardcodes the vanilla task id list");
        }
    }
}
