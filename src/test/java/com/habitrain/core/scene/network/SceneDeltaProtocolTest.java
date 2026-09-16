package com.habitrain.core.scene.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量协商两个包（探测 C2S / 应答 S2C）的编解码契约。
 *
 * <p>这两个包是**新增**包型而不是改既有包布局，正是为了保证旧客户端从不发送探测、也就永远
 * 收不到应答——四种版本组合都安全。字段顺序与状态字必须钉住。</p>
 */
class SceneDeltaProtocolTest {
    private static final String TARGET = "b".repeat(64);
    private static final String BASE = "c".repeat(64);
    private static final String PATCH = "d".repeat(64);

    @Test
    void probeRoundTrips() {
        SceneAssetDeltaProbeC2S original = new SceneAssetDeltaProbeC2S("map1::habiscene::bg", TARGET);
        ByteBuf buffer = Unpooled.buffer();
        SceneAssetDeltaProbeC2S.CODEC.encode(buffer, original);
        SceneAssetDeltaProbeC2S decoded = SceneAssetDeltaProbeC2S.CODEC.decode(buffer);

        assertEquals("map1::habiscene::bg", decoded.mapKey());
        assertEquals(TARGET, decoded.targetSha256());
    }

    @Test
    void offerRoundTripsWithDelta() {
        SceneAssetDeltaOfferS2C original = new SceneAssetDeltaOfferS2C(
                TARGET, SceneAssetDeltaOfferS2C.Status.OK, BASE, PATCH, 123456L);

        ByteBuf buffer = Unpooled.buffer();
        SceneAssetDeltaOfferS2C.CODEC.encode(buffer, original);
        SceneAssetDeltaOfferS2C decoded = SceneAssetDeltaOfferS2C.CODEC.decode(buffer);

        assertEquals(TARGET, decoded.targetSha256());
        assertEquals(SceneAssetDeltaOfferS2C.Status.OK, decoded.status());
        assertEquals(BASE, decoded.baseSha256());
        assertEquals(PATCH, decoded.patchSha256());
        assertEquals(123456L, decoded.patchBytes());
        assertTrue(decoded.hasDelta());
    }

    @Test
    void offerRoundTripsWithoutDelta() {
        ByteBuf buffer = Unpooled.buffer();
        SceneAssetDeltaOfferS2C.CODEC.encode(buffer, SceneAssetDeltaOfferS2C.none(TARGET));
        SceneAssetDeltaOfferS2C decoded = SceneAssetDeltaOfferS2C.CODEC.decode(buffer);

        assertEquals(SceneAssetDeltaOfferS2C.Status.NO_DELTA, decoded.status());
        assertFalse(decoded.hasDelta());
        assertEquals("", decoded.baseSha256());
        assertEquals(0L, decoded.patchBytes());
    }

    /** 状态是"有补丁但字段不全"时一律当作没有：半条信息不能拿去下补丁。 */
    @Test
    void halfFilledOfferIsNotUsableAsDelta() {
        assertFalse(new SceneAssetDeltaOfferS2C(TARGET, SceneAssetDeltaOfferS2C.Status.OK,
                BASE, "", 100L).hasDelta());
        assertFalse(new SceneAssetDeltaOfferS2C(TARGET, SceneAssetDeltaOfferS2C.Status.OK,
                BASE, PATCH, 0L).hasDelta());
        assertFalse(new SceneAssetDeltaOfferS2C(TARGET, SceneAssetDeltaOfferS2C.Status.NO_DELTA,
                BASE, PATCH, 100L).hasDelta());
    }

    @Test
    void unknownStatusIsRejected() {
        ByteBuf buffer = Unpooled.buffer();
        ScenePacketIo.writeHash(buffer, TARGET);
        buffer.writeByte(42);
        ScenePacketIo.writeHash(buffer, BASE);
        ScenePacketIo.writeHash(buffer, PATCH);
        buffer.writeLong(1L);
        assertThrows(DecoderException.class, () -> SceneAssetDeltaOfferS2C.CODEC.decode(buffer));
    }

    @Test
    void negativePatchSizeIsRejected() {
        ByteBuf buffer = Unpooled.buffer();
        ScenePacketIo.writeHash(buffer, TARGET);
        buffer.writeByte(SceneAssetDeltaOfferS2C.Status.OK.wireId());
        ScenePacketIo.writeHash(buffer, BASE);
        ScenePacketIo.writeHash(buffer, PATCH);
        buffer.writeLong(-5L);
        assertThrows(DecoderException.class, () -> SceneAssetDeltaOfferS2C.CODEC.decode(buffer));
    }

    /** 探测包编出来必须是定长 8 + 32 + 32 + 32 字节（两个 int 长度前缀 + 两个 64 字节 hash）。 */
    @Test
    void probeWireSizeIsStable() {
        ByteBuf buffer = Unpooled.buffer();
        SceneAssetDeltaProbeC2S.CODEC.encode(buffer, new SceneAssetDeltaProbeC2S("map1", TARGET));
        assertEquals(4 + 4 + 4 + 64, buffer.readableBytes());
    }
}
