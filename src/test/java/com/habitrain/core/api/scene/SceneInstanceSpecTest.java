package com.habitrain.core.api.scene;

import com.habitrain.core.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.scene.model.SceneMotionMode;
import com.habitrain.core.scene.model.SceneOrbitAxis;
import com.habitrain.core.scene.model.SceneProfile;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SceneInstanceSpec} / {@link SceneProfileBuilder} / {@link SceneInstanceAnchor} 的值语义测试。 */
class SceneInstanceSpecTest {

    @Test
    void builderDefaultsMatchDocumentedBehaviour() {
        SceneInstanceSpec spec = SceneInstanceSpec.builder("mymod:window")
                .assetKey("mymap")
                .build();

        assertTrue(spec.isValid(), () -> spec.validationError().orElse(""));
        assertEquals("mymod", spec.ownerId(), "owner 默认取自 ID 命名空间");
        assertEquals(SceneInstanceSpec.AUTO_START_GAME_TIME, spec.startGameTime());
        assertEquals(1.0, spec.timeScale(), 1e-9);
        assertEquals(0L, spec.durationTicks(), "默认永久存活");
        assertFalse(spec.hasLifetime());
        assertFalse(spec.paused());
        assertTrue(spec.visibleToAll());
        assertTrue(spec.anchor().isWorld());
        assertTrue(spec.profile().isEnabled(), "构建器新建的 profile 默认启用");
    }

    @Test
    void validationRejectsBadInput() {
        assertTrue(SceneInstanceSpec.builder("").assetKey("a").build().validationError().isPresent());
        assertTrue(SceneInstanceSpec.builder("bad id").assetKey("a").build().validationError().isPresent());
        assertTrue(SceneInstanceSpec.builder("ok").assetKey(" ").build().validationError().isPresent());
        assertTrue(SceneInstanceSpec.builder("ok").assetKey("a").timeScale(-1.0).build()
                .validationError().isPresent(), "负时间缩放不支持");
        assertTrue(SceneInstanceSpec.builder("ok").assetKey("a").timeScale(64.0).build()
                .validationError().isPresent(), "超出上限");
        assertTrue(SceneInstanceSpec.builder("ok").assetKey("a").visibleTo(new UUID[0]).build()
                .validationError().isPresent(), "白名单为空等于谁都看不到");
    }

    @Test
    void profileBuilderCoversEveryField() {
        SceneProfile profile = SceneProfileBuilder.create()
                .enabled(true)
                .bounds(-8, 60, -8, 8, 76, 8)
                .displayOrigin(1.5, 64.0, -300.0)
                .pivotLocal(0.5, 1.0, -0.5)
                .direction(-1.0, 0.5, 0.0)
                .speed(40.0)
                .rotation(15.0, -5.0, 2.5)
                .phaseOffset(12.0)
                .loopCustom(true, 300.0)
                .render(r -> r.maxDistance(320.0).translucent(false))
                .sound(s -> s.enabled(true).soundId("mymod:rumble").volume(0.4).pitch(1.2).fadeTicks(40))
                .shake(s -> s.intense())
                .build();

        assertTrue(profile.isEnabled());
        assertEquals(17, profile.getSourceBounds().sizeX(), "bounds(min..max) 是闭区间，含两端");
        assertEquals(1.5, profile.getDisplayOrigin()[0], 1e-9);
        assertEquals(-0.5, profile.getPivotLocal()[2], 1e-9);
        assertEquals(1.0, Math.sqrt(java.util.Arrays.stream(profile.getDirection())
                .map(v -> v * v).sum()), 1e-6, "方向必须归一化");
        assertEquals(40.0, profile.getSpeedBlocksPerSecond(), 1e-9);
        assertEquals(2.5, profile.getRotationDegrees().rollDegrees(), 1e-4);
        assertEquals(12.0, profile.getPhaseOffsetBlocks(), 1e-9);
        assertEquals(SceneLoopDistanceMode.CUSTOM, profile.getLoop().getDistanceMode());
        assertEquals(300.0, profile.getLoop().getDistanceBlocks(), 1e-9);
        assertEquals(320.0, profile.getRender().getMaxDistanceBlocks(), 1e-9);
        assertFalse(profile.getRender().isRenderTranslucent());
        assertEquals("mymod:rumble", profile.getOutsideSound().getSoundId());
        assertEquals(0.4, profile.getOutsideSound().getVolume(), 1e-6);
        assertEquals(SceneMotionMode.LINEAR, profile.getMotionMode());
        assertTrue(profile.getShake().isEnabled());
    }

    @Test
    void orbitBuilderSwitchesModeAndKeepsDetail() {
        SceneProfile profile = SceneProfileBuilder.create()
                .orbit(o -> o.centerWorld(10, 70, 20)
                        .axis(SceneOrbitAxis.Y)
                        .startAngle(45.0)
                        .sweep(180.0)
                        .clockwise(false)
                        .angularSpeed(60.0)
                        .verticalBob(4.0)
                        .radialBob(2.0)
                        .bobCycles(0.5)
                        .instances(6)
                        .instanceSpread(60.0)
                        .rotateModelWithOrbit(true))
                .build();

        assertEquals(SceneMotionMode.ORBIT, profile.getMotionMode());
        assertEquals(SceneOrbitAxis.Y, profile.getOrbit().getAxis());
        assertEquals(45.0, profile.getOrbit().getStartAngleDegrees(), 1e-9);
        assertEquals(180.0, profile.getOrbit().getSweepDegrees(), 1e-9);
        assertFalse(profile.getOrbit().isClockwise());
        assertEquals(6, profile.getOrbit().getInstanceCount());
        assertEquals(4.0, profile.getOrbit().getVerticalBobAmplitudeBlocks(), 1e-9);
        assertEquals(10.0, profile.getOrbit().getCenterWorld()[0], 1e-9);
    }

    @Test
    void fromKeepsEnabledFlagWhileCreateEnablesIt() {
        SceneProfile disabled = SceneProfile.createDefault();
        disabled.setEnabled(false);
        assertFalse(SceneProfileBuilder.from(disabled).build().isEnabled(),
                "from(...) 必须保留原 enabled");
        assertFalse(SceneProfileBuilder.from(disabled).speed(5.0).build().isEnabled());
    }

    @Test
    void toBuilderPreservesInstanceIdentityAndClock() {
        UUID viewer = UUID.randomUUID();
        SceneInstanceSpec original = SceneInstanceSpec.builder("mymod:scene")
                .assetKey("mymap")
                .startGameTime(1234L)
                .timeScale(2.0)
                .paused(true)
                .priority(7)
                .tag("train")
                .anchor(SceneInstanceAnchor.player(viewer, 1.0, 2.0, 3.0))
                .visibleTo(viewer)
                .build();

        SceneInstanceSpec copy = original.toBuilder().build();

        assertEquals(original, copy);
        assertEquals(0.0, copy.headStartSeconds(), 1e-9, "headStart 是一次性折算，复制后清零");
        assertEquals(1234L, copy.startGameTime());
        assertEquals(2.0, copy.timeScale(), 1e-9);
        assertEquals(7, copy.priority());
        assertEquals(Set.of("train"), copy.tags());
        assertTrue(copy.anchor().isPlayer());
        assertFalse(copy.visibleToAll());
        assertEquals(Set.of(viewer), copy.visibleTo());
    }

    @Test
    void profileEditorMutatesInPlaceWithoutDroppingFields() {
        SceneInstanceSpec spec = SceneInstanceSpec.builder("mymod:scene")
                .assetKey("mymap")
                .profileEditor(p -> p.speed(11.0).renderDistance(128.0))
                .build();
        SceneInstanceSpec updated = spec.toBuilder()
                .profileEditor(p -> p.speed(22.0))
                .build();

        assertEquals(22.0, updated.profile().getSpeedBlocksPerSecond(), 1e-9);
        assertEquals(128.0, updated.profile().getRender().getMaxDistanceBlocks(), 1e-9,
                "未提及的字段必须保留");
        assertNotEquals(spec, updated);
    }

    @Test
    void anchorFactoriesAndJsonRoundTrip() {
        UUID viewer = UUID.randomUUID();
        SceneInstanceAnchor playerAnchor = SceneInstanceAnchor.player(viewer, 0.0, 1.0, 24.0);
        assertTrue(playerAnchor.isPlayer());
        assertTrue(playerAnchor.requiresRuntimeResolution());

        SceneInstanceAnchor decoded = SceneInstanceAnchor.fromJson(playerAnchor.toJson());
        assertEquals(playerAnchor, decoded);

        SceneInstanceAnchor entity = SceneInstanceAnchor.entity(42, -3.0, 0.0, 0.0);
        assertEquals(entity, SceneInstanceAnchor.fromJson(entity.toJson()));
        assertTrue(SceneInstanceAnchor.world().isWorld());
        assertFalse(SceneInstanceAnchor.world().requiresRuntimeResolution());
        assertEquals(SceneInstanceAnchor.WORLD, SceneInstanceAnchor.player(null, 1, 2, 3),
                "null 玩家退化为世界锚点");
    }

    @Test
    void spawnResultExposesStatus() {
        assertTrue(SceneSpawnResult.ok("mymod:scene", "已注册").isSuccess());
        SceneSpawnResult failure = SceneSpawnResult.failure(
                SceneSpawnResult.SceneSpawnStatus.GLOBAL_DISABLED, "mymod:scene", "全局关闭");
        assertFalse(failure.isSuccess());
        assertEquals(SceneSpawnResult.SceneSpawnStatus.GLOBAL_DISABLED, failure.status());
    }
}
