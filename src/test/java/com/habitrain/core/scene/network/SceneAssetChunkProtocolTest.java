package com.habitrain.core.scene.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分片传输协议的编解码契约。
 *
 * <p>这批包是破坏性变更（新增 transferId/requestId），字段顺序一旦错位就会在线上产生
 * 难以定位的乱码，因此逐字段固定下来。</p>
 */
class SceneAssetChunkProtocolTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void chunkRequestRoundTripsCorrelationIds() {
        SceneAssetChunkRequestC2S original = new SceneAssetChunkRequestC2S(
                HASH, 0x1122334455667788L, 42L, 131072L, 65536);

        ByteBuf buffer = Unpooled.buffer();
        SceneAssetChunkRequestC2S.CODEC.encode(buffer, original);
        SceneAssetChunkRequestC2S decoded = SceneAssetChunkRequestC2S.CODEC.decode(buffer);

        assertEquals(HASH, decoded.sha256());
        assertEquals(0x1122334455667788L, decoded.transferId());
        assertEquals(42L, decoded.requestId());
        assertEquals(131072L, decoded.chunkOffset());
        assertEquals(65536, decoded.chunkSize());
    }

    @Test
    void chunkRequestWireSizeIs96BytesForA64ByteHash() {
        ByteBuf buffer = Unpooled.buffer();
        SceneAssetChunkRequestC2S.CODEC.encode(buffer,
                new SceneAssetChunkRequestC2S(HASH, 1L, 1L, 0L, 65536));
        // hashLen(4) + hash(64) + transferId(8) + requestId(8) + offset(8) + chunkSize(4)
        assertEquals(96, buffer.readableBytes());
    }

    @Test
    void chunkDataRoundTripsCorrelationIdsAndPayload() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);

        SceneAssetChunkS2C original = new SceneAssetChunkS2C(
                HASH, 7L, 9L, 65536L, 1_048_576L, data, 0xDEADBEEFL);

        ByteBuf buffer = Unpooled.buffer();
        SceneAssetChunkS2C.CODEC.encode(buffer, original);
        SceneAssetChunkS2C decoded = SceneAssetChunkS2C.CODEC.decode(buffer);

        assertEquals(HASH, decoded.sha256());
        assertEquals(7L, decoded.transferId());
        assertEquals(9L, decoded.requestId());
        assertEquals(65536L, decoded.chunkOffset());
        assertEquals(1_048_576L, decoded.totalSize());
        assertEquals(0xDEADBEEFL, decoded.crc32());
        assertArrayEquals(data, decoded.data());
    }

    @Test
    void chunkDataRejectsOversizedPayload() {
        ByteBuf buffer = Unpooled.buffer();
        ScenePacketIoFixture.writeHash(buffer, HASH);
        buffer.writeLong(1L);
        buffer.writeLong(1L);
        buffer.writeLong(0L);
        buffer.writeLong(1L);
        buffer.writeInt(SceneAssetChunkS2C.MAX_CHUNK_BYTES + 1);
        assertThrows(DecoderException.class, () -> SceneAssetChunkS2C.CODEC.decode(buffer));
    }

    @Test
    void chunkDataRejectsNegativePayloadLength() {
        ByteBuf buffer = Unpooled.buffer();
        ScenePacketIoFixture.writeHash(buffer, HASH);
        buffer.writeLong(1L);
        buffer.writeLong(1L);
        buffer.writeLong(0L);
        buffer.writeLong(1L);
        buffer.writeInt(-1);
        assertThrows(DecoderException.class, () -> SceneAssetChunkS2C.CODEC.decode(buffer));
    }

    @Test
    void statusPacketRoundTripsEveryStatus() {
        for (SceneAssetChunkStatusS2C.Status status : SceneAssetChunkStatusS2C.Status.values()) {
            SceneAssetChunkStatusS2C original = new SceneAssetChunkStatusS2C(
                    HASH, 3L, 4L, 65536L, status, 250);
            ByteBuf buffer = Unpooled.buffer();
            SceneAssetChunkStatusS2C.CODEC.encode(buffer, original);
            SceneAssetChunkStatusS2C decoded = SceneAssetChunkStatusS2C.CODEC.decode(buffer);

            assertEquals(HASH, decoded.sha256());
            assertEquals(3L, decoded.transferId());
            assertEquals(4L, decoded.requestId());
            assertEquals(65536L, decoded.chunkOffset());
            assertEquals(status, decoded.status(), "status 必须按 wireId 往返，不依赖枚举序号");
            assertEquals(250, decoded.retryAfterMillis());
        }
    }

    @Test
    void onlyBusyAndStaleAreNonTerminal() {
        assertFalse(SceneAssetChunkStatusS2C.Status.BUSY.isTerminalFailure());
        assertFalse(SceneAssetChunkStatusS2C.Status.STALE.isTerminalFailure());
        assertTrue(SceneAssetChunkStatusS2C.Status.REJECTED.isTerminalFailure());
        assertTrue(SceneAssetChunkStatusS2C.Status.NOT_AUTHORIZED.isTerminalFailure());
        assertTrue(SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE.isTerminalFailure());
    }

    @Test
    void unknownStatusIdIsRejected() {
        assertThrows(DecoderException.class, () -> SceneAssetChunkStatusS2C.Status.fromWireId(99));
    }

    @Test
    void oversizedHashIsRejectedOnEncode() {
        String tooLong = "a".repeat(ScenePacketIoFixture.MAX_HASH_BYTES + 1);
        ByteBuf buffer = Unpooled.buffer();
        assertThrows(EncoderException.class, () -> SceneAssetChunkRequestC2S.CODEC.encode(buffer,
                new SceneAssetChunkRequestC2S(tooLong, 1L, 1L, 0L, 65536)));
    }

    /** 把包内私有的字符串写入逻辑暴露给测试；与实现共用同一份常量。 */
    static final class ScenePacketIoFixture {
        static final int MAX_HASH_BYTES = 128;

        static void writeHash(ByteBuf buffer, String hash) {
            byte[] bytes = hash.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.writeInt(bytes.length);
            buffer.writeBytes(bytes);
        }
    }
}
