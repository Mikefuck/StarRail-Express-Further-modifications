package com.habitrain.core.api.scene.model;

import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneProfileBuilder;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SceneInstance} 的运行时判定：时间轴、暂停、过期、可见性、维度归属。 */
class SceneInstanceTest {

    private static SceneInstance build(SceneInstanceSpec spec, long startGameTime,
                                       long expireAtGameTime, long pausedAtGameTime) {
        return new SceneInstance(spec, "b".repeat(64), 1, expireAtGameTime, pausedAtGameTime, 0L);
    }

    private static SceneInstanceSpec.Builder base(String id) {
        return SceneInstanceSpec.builder(id).assetKey("mymap");
    }

    @Test
    void elapsedSecondsFollowsTimeScaleAndPartialTick() {
        SceneInstance instance = build(base("mymod:a").startGameTime(100L).timeScale(1.0).build(),
                100L, -1L, -1L);
        assertEquals(1.0, instance.elapsedSeconds(120L, 0.0f), 1e-9);
        assertEquals(1.5, instance.elapsedSeconds(120L, 10.0f), 1e-9);

        SceneInstance fast = build(base("mymod:b").startGameTime(100L).timeScale(2.0).build(),
                100L, -1L, -1L);
        assertEquals(4.0, fast.elapsedSeconds(140L, 0.0f), 1e-9);

        SceneInstance frozen = build(base("mymod:c").startGameTime(100L).timeScale(0.0).build(),
                100L, -1L, -1L);
        assertEquals(0.0, frozen.elapsedSeconds(500L, 5.0f), 1e-9);

        SceneInstance future = build(base("mymod:d").startGameTime(500L).build(), 500L, -1L, -1L);
        assertEquals(0.0, future.elapsedSeconds(100L, 0.0f), 1e-9, "起点之前不产生负时间");
    }

    @Test
    void pausedInstanceFreezesAtPauseMoment() {
        SceneInstance instance = build(base("mymod:paused").startGameTime(100L).paused(true).build(),
                100L, -1L, 160L);
        assertEquals(3.0, instance.elapsedSeconds(400L, 8.0f), 1e-9,
                "暂停后时间轴停在暂停时刻，partialTick 不参与");
    }

    @Test
    void expiryAndRemainingTicks() {
        SceneInstance instance = build(base("mymod:timed").startGameTime(0L).durationTicks(200L).build(),
                0L, 200L, -1L);
        assertEquals(200L, instance.remainingTicks(0L));
        assertEquals(50L, instance.remainingTicks(150L));
        assertFalse(instance.isExpired(199L));
        assertTrue(instance.isExpired(200L));
        assertTrue(instance.isExpired(1000L));

        SceneInstance permanent = build(base("mymod:forever").build(), 0L, -1L, -1L);
        assertFalse(permanent.isExpired(Long.MAX_VALUE));
        assertEquals(-1L, permanent.remainingTicks(Long.MAX_VALUE));
        assertFalse(permanent.hasAsset() && permanent.expireAtGameTime() >= 0);
    }

    @Test
    void visibilityFiltersByPlayerAndDimension() {
        UUID allowed = UUID.randomUUID();
        SceneInstance restricted = build(base("mymod:private").visibleTo(allowed).build(), 0L, -1L, -1L);
        assertTrue(restricted.isVisibleTo(allowed));
        assertFalse(restricted.isVisibleTo(UUID.randomUUID()));

        SceneInstance publicScene = build(base("mymod:public").build(), 0L, -1L, -1L);
        assertTrue(publicScene.isVisibleTo(null), "全可见实例对任意玩家都成立");

        SceneInstance overworld = build(base("mymod:overworld").dimension("minecraft:overworld").build(),
                0L, -1L, -1L);
        assertTrue(overworld.belongsTo("minecraft:overworld"));
        assertFalse(overworld.belongsTo("minecraft:the_nether"));
        assertFalse(overworld.belongsTo(null));
        assertFalse(publicScene.belongsTo("minecraft:overworld"),
                "未指定维度的 spec 在 spawn 前没有维度归属");
    }

    @Test
    void tagsAndRevisionTracking() {
        SceneInstance instance = build(base("mymod:tagged").tag("train").tag("window").build(),
                0L, -1L, -1L);
        assertTrue(instance.hasTag("train"));
        assertTrue(instance.hasTag("window"));
        assertFalse(instance.hasTag("carriage"));
        assertEquals(2, instance.tags().size());

        SceneInstance refreshed = instance.withAssetHash("c".repeat(64));
        assertEquals(2, refreshed.revision(), "更新一次 revision +1");
        assertEquals("c".repeat(64), refreshed.assetHash());
        assertEquals(instance.id(), refreshed.id());
        assertEquals(instance.createdAtMillis(), refreshed.createdAtMillis());
    }

    @Test
    void anchoredProfileKeepsMotionParameters() {
        SceneInstance instance = build(base("mymod:anchored")
                        .profile(SceneProfileBuilder.create().speed(30.0).build())
                        .build(), 0L, -1L, -1L);
        assertTrue(instance.profile().isEnabled());
        assertEquals(30.0, instance.profile().getSpeedBlocksPerSecond(), 1e-9);
        assertTrue(instance.anchor().isWorld());
    }
}
