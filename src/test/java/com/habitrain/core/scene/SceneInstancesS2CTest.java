package com.habitrain.core.scene;

import com.habitrain.core.api.scene.SceneInstanceAnchor;
import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneProfileBuilder;
import com.habitrain.core.scene.model.SceneInstance;
import com.habitrain.core.scene.network.SceneInstancesS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SceneInstancesS2C} 编解码与"实例数量无上限"语义测试。 */
class SceneInstancesS2CTest {

    private static SceneInstance instance(String id, long startGameTime, double timeScale) {
        SceneInstanceSpec spec = SceneInstanceSpec.builder(id)
                .assetKey("mymap")
                .startGameTime(startGameTime)
                .timeScale(timeScale)
                .priority(3)
                .tag("train")
                .profile(SceneProfileBuilder.create()
                        .displayOrigin(1.0, 64.0, -20.0)
                        .direction(-1.0, 0.0, 0.0)
                        .speed(25.0)
                        .loopCustom(true, 256.0)
                        .render(r -> r.maxDistance(300.0))
                        .build())
                .build();
        return new SceneInstance(spec, "a".repeat(64), 5, startGameTime + 400, -1L, 1_700_000_000_000L);
    }

    @Test
    void encodesAndDecodesAllFields() {
        SceneInstance original = instance("mymod:window_a", 500L, 2.0);
        ByteBuf buffer = Unpooled.buffer();
        SceneInstancesS2C.CODEC.encode(buffer, SceneInstancesS2C.upsert(original));

        SceneInstancesS2C decoded = SceneInstancesS2C.CODEC.decode(buffer);
        assertFalse(decoded.clear());
        assertTrue(decoded.removals().isEmpty());
        assertEquals(1, decoded.upserts().size());

        SceneInstance roundTripped = decoded.upserts().get(0);
        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.ownerId(), roundTripped.ownerId());
        assertEquals(original.dimensionKey(), roundTripped.dimensionKey());
        assertEquals(original.assetKey(), roundTripped.assetKey());
        assertEquals(original.assetHash(), roundTripped.assetHash());
        assertEquals(original.startGameTime(), roundTripped.startGameTime());
        assertEquals(original.revision(), roundTripped.revision());
        assertEquals(original.durationTicks(), roundTripped.durationTicks());
        assertEquals(2.0, roundTripped.timeScale(), 1e-9);
        assertEquals(3, roundTripped.priority());
        assertEquals(original.tags(), roundTripped.tags());
        assertEquals(25.0, roundTripped.profile().getSpeedBlocksPerSecond(), 1e-9);
        assertEquals(300.0, roundTripped.profile().getRender().getMaxDistanceBlocks(), 1e-9);
        assertTrue(roundTripped.visibleToAll());
        assertTrue(roundTripped.anchor().isWorld());
    }

    @Test
    void anchorAndVisibilitySurviveRoundTrip() {
        UUID viewer = UUID.randomUUID();
        SceneInstanceSpec spec = SceneInstanceSpec.builder("mymod:anchored")
                .assetKey("mymap")
                .anchor(SceneInstanceAnchor.entity(77, 0.0, 3.0, 0.0))
                .visibleTo(viewer)
                .build();
        SceneInstance original = new SceneInstance(spec, "", 1, -1L, -1L, 0L);

        ByteBuf buffer = Unpooled.buffer();
        SceneInstancesS2C.CODEC.encode(buffer, SceneInstancesS2C.upsert(original));
        SceneInstance decoded = SceneInstancesS2C.CODEC.decode(buffer).upserts().get(0);

        assertTrue(decoded.anchor().isEntity());
        assertEquals(77, decoded.anchor().entityId());
        assertEquals(3.0, decoded.anchor().offsetY(), 1e-9);
        assertFalse(decoded.visibleToAll());
        assertEquals(java.util.Set.of(viewer), decoded.visibleTo());
        assertTrue(decoded.isVisibleTo(viewer));
        assertFalse(decoded.isVisibleTo(UUID.randomUUID()));
    }

    @Test
    void removalAndClearPacketsRoundTrip() {
        ByteBuf removal = Unpooled.buffer();
        SceneInstancesS2C.CODEC.encode(removal, SceneInstancesS2C.removal("mymod:a"));
        SceneInstancesS2C decodedRemoval = SceneInstancesS2C.CODEC.decode(removal);
        assertEquals(List.of("mymod:a"), decodedRemoval.removals());
        assertTrue(decodedRemoval.upserts().isEmpty());

        ByteBuf clear = Unpooled.buffer();
        SceneInstancesS2C.CODEC.encode(clear, SceneInstancesS2C.clearAll());
        assertTrue(SceneInstancesS2C.CODEC.decode(clear).clear());
    }

    @Test
    void simultaneousScenesHaveNoCountLimit() {
        // 一次连接上并存 2000 个实例：拆成多包同步即可，协议侧没有"总数上限"这种字段。
        int total = 2000;
        List<SceneInstance> instances = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            instances.add(instance("mymod:scene_" + i, 100L + i, 1.0));
        }

        List<SceneInstance> received = new ArrayList<>(total);
        int batchSize = 64;
        for (int offset = 0; offset < total; offset += batchSize) {
            List<SceneInstance> batch = instances.subList(offset, Math.min(total, offset + batchSize));
            ByteBuf buffer = Unpooled.buffer();
            SceneInstancesS2C.CODEC.encode(buffer, new SceneInstancesS2C(batch, List.of(), false));
            received.addAll(SceneInstancesS2C.CODEC.decode(buffer).upserts());
        }

        assertEquals(total, received.size());
        assertEquals("mymod:scene_1999", received.get(total - 1).id());
        assertEquals(total, received.stream().map(SceneInstance::id).distinct().count());
    }

    @Test
    void codecRejectsOversizedPacketButNotInstanceTotal() {
        List<SceneInstance> tooMany = new ArrayList<>();
        for (int i = 0; i <= SceneInstancesS2C.MAX_ENTRIES_PER_PACKET; i++) {
            tooMany.add(instance("mymod:overflow_" + i, 0L, 1.0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SceneInstancesS2C(tooMany, List.of(), false),
                "单包条目有上限（包大小保护），但实例总数没有");
    }

    @Test
    void profileJsonLimitGuardsPackets() {
        // 极端插件把 profile 撑爆时编码必须失败，而不是发出一个不可解析的巨型包。
        SceneProfileBuilder builder = SceneProfileBuilder.create();
        StringBuilder huge = new StringBuilder("mymod:");
        huge.append("x".repeat(SceneInstancesS2C.MAX_PROFILE_JSON_BYTES));
        builder.sound(s -> s.enabled(true).soundId(huge.toString()));
        SceneInstance oversized = new SceneInstance(
                SceneInstanceSpec.builder("mymod:huge").assetKey("mymap").profile(builder.build()).build(),
                "", 1, -1L, -1L, 0L);

        ByteBuf buffer = Unpooled.buffer();
        assertThrows(RuntimeException.class,
                () -> SceneInstancesS2C.CODEC.encode(buffer, SceneInstancesS2C.upsert(oversized)));
        buffer.release();
    }
}
