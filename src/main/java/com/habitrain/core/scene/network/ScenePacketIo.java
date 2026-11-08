package com.habitrain.core.scene.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.nio.charset.StandardCharsets;

/**
 * 场景网络包共用的字符串编解码助手。
 *
 * <p>原本每个包各自复制一份 {@code readString}/{@code writeString}；分片包新增
 * transferId/requestId 后包数量增加，这里统一为一份，长度上限按用途区分：
 * sha256 十六进制 64 字节，其余标识串沿用旧实现的宽松上限。</p>
 */
final class ScenePacketIo {
    /** sha256 十六进制串固定 64 字节，留一倍余量。 */
    static final int MAX_HASH_BYTES = 128;
    /** 地图键、指纹等标识串。 */
    /**
     * 审核 S-07：与服务端 {@code SceneInstanceSpec.MAX_ID_LENGTH = 128} 对齐——
     * 128 个字符在 UTF-8 下最多占 512 字节，旧值 32767 远宽于校验上限。
     */
    static final int MAX_ID_BYTES = 512;
    /** SCN-SRV-15：注册表指纹可能超过 512 字节，与 SceneAssetPrefetchS2C / SceneStagingOfferS2C 保持 32767 上限一致。 */
    static final int MAX_FINGERPRINT_BYTES = 32767;

    private ScenePacketIo() {}

    static String readHash(ByteBuf buf) {
        return readString(buf, MAX_HASH_BYTES, "Hash");
    }

    static void writeHash(ByteBuf buf, String hash) {
        writeString(buf, hash, MAX_HASH_BYTES, "Hash");
    }

    static String readId(ByteBuf buf) {
        return readString(buf, MAX_ID_BYTES, "String");
    }

    static void writeId(ByteBuf buf, String value) {
        writeString(buf, value, MAX_ID_BYTES, "String");
    }

    static String readFingerprint(ByteBuf buf) {
        return readString(buf, MAX_FINGERPRINT_BYTES, "Fingerprint");
    }

    static void writeFingerprint(ByteBuf buf, String fingerprint) {
        writeString(buf, fingerprint, MAX_FINGERPRINT_BYTES, "Fingerprint");
    }

    private static String readString(ByteBuf buf, int maxBytes, String label) {
        int len = buf.readInt();
        if (len < 0) throw new DecoderException(label + " negative length: " + len);
        if (len == 0) return "";
        if (len > maxBytes) throw new DecoderException(label + " too long: " + len);
        if (len > buf.readableBytes()) throw new DecoderException(label + " exceeds readable bytes: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value, int maxBytes, String label) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new EncoderException(label + " too long: " + bytes.length);
        buf.writeInt(bytes.length);
        if (bytes.length > 0) buf.writeBytes(bytes);
    }
}
