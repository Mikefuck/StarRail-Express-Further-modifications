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
 * C2S 网络包 - 管理员请求生成或重新构建指定地图的场景资产。
 */
public final class SceneAssetBuildRequestC2S implements CustomPacketPayload {
    public static final Type<SceneAssetBuildRequestC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_build_req"));

    private final String mapKey;
    private final boolean force;

    public SceneAssetBuildRequestC2S(String mapKey, boolean force) {
        this.mapKey = mapKey != null ? mapKey : "";
        this.force = force;
    }

    public String mapKey() { return mapKey; }
    public boolean force() { return force; }

    public static final StreamCodec<ByteBuf, SceneAssetBuildRequestC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetBuildRequestC2S decode(ByteBuf buf) {
            String map = readString(buf);
            boolean force = buf.readBoolean();
            return new SceneAssetBuildRequestC2S(map, force);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetBuildRequestC2S payload) {
            writeString(buf, payload.mapKey);
            buf.writeBoolean(payload.force);
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
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
