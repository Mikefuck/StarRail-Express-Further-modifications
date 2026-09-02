package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 资产捕获与构建进度通知。
 */
public final class SceneAssetBuildProgressS2C implements CustomPacketPayload {
    public static final Type<SceneAssetBuildProgressS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_build_prog"));

    private final String mapKey;
    private final String state; // "IDLE", "CAPTURING", "COMPRESSING", "SAVING", "COMPLETED", "FAILED"
    private final float progress; // 0.0 .. 1.0
    private final int processedSections;
    private final int totalSections;
    private final String statusMessage;

    public SceneAssetBuildProgressS2C(String mapKey, String state, float progress,
                                      int processedSections, int totalSections, String statusMessage) {
        this.mapKey = mapKey != null ? mapKey : "";
        this.state = state != null ? state : "IDLE";
        this.progress = Math.max(0.0f, Math.min(1.0f, progress));
        this.processedSections = processedSections;
        this.totalSections = totalSections;
        this.statusMessage = statusMessage != null ? statusMessage : "";
    }

    public String mapKey() { return mapKey; }
    public String state() { return state; }
    public float progress() { return progress; }
    public int processedSections() { return processedSections; }
    public int totalSections() { return totalSections; }
    public String statusMessage() { return statusMessage; }

    public boolean isCompleted() { return "COMPLETED".equalsIgnoreCase(state); }
    public boolean isFailed() { return "FAILED".equalsIgnoreCase(state); }

    public static final StreamCodec<ByteBuf, SceneAssetBuildProgressS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetBuildProgressS2C decode(ByteBuf buf) {
            String map = readString(buf);
            String state = readString(buf);
            float progress = buf.readFloat();
            int processed = buf.readInt();
            int total = buf.readInt();
            String msg = readString(buf);
            return new SceneAssetBuildProgressS2C(map, state, progress, processed, total, msg);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetBuildProgressS2C payload) {
            writeString(buf, payload.mapKey);
            writeString(buf, payload.state);
            buf.writeFloat(payload.progress);
            buf.writeInt(payload.processedSections);
            buf.writeInt(payload.totalSections);
            writeString(buf, payload.statusMessage);
        }
    };

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len <= 0) return "";
        if (len > 32767) throw new DecoderException("String too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) throw new EncoderException("String too long: " + bytes.length);
        buf.writeInt(bytes.length);
        if (bytes.length > 0) buf.writeBytes(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
