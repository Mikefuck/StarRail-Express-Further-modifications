package com.habitrain.core.scene.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 场景运行时状态下发（开局/重连/手动启停）。
 */
public final class SceneRuntimeStateS2C implements CustomPacketPayload {
    public static final Type<SceneRuntimeStateS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_runtime_state"));

    private static final int MAX_STRING_LENGTH = 1048576;

    private final SceneRuntimeState state;

    public SceneRuntimeStateS2C(SceneRuntimeState state) {
        this.state = state != null ? state : SceneRuntimeState.INACTIVE;
    }

    public SceneRuntimeState state() { return state; }

    public static final StreamCodec<ByteBuf, SceneRuntimeStateS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneRuntimeStateS2C decode(ByteBuf buf) {
            boolean active = buf.readBoolean();
            long startTime = buf.readLong();
            int revision = buf.readInt();
            String mapKey = readString(buf);
            String assetHash = readString(buf);
            String profileJson = readString(buf);

            SceneProfile profile;
            try {
                JsonObject obj = JsonParser.parseString(profileJson).getAsJsonObject();
                profile = SceneProfile.fromJson(obj);
            } catch (Exception e) {
                profile = SceneProfile.createDefault();
            }

            SceneRuntimeState runtimeState = new SceneRuntimeState(active, startTime, revision, mapKey, assetHash, profile);
            return new SceneRuntimeStateS2C(runtimeState);
        }

        @Override
        public void encode(ByteBuf buf, SceneRuntimeStateS2C payload) {
            SceneRuntimeState s = payload.state;
            buf.writeBoolean(s.isActive());
            buf.writeLong(s.getStartGameTime());
            buf.writeInt(s.getProfileRevision());
            writeString(buf, s.getMapKey());
            writeString(buf, s.getAssetHash());
            writeString(buf, s.getProfile().toJson().toString());
        }
    };

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len <= 0) return "";
        if (len > MAX_STRING_LENGTH) throw new DecoderException("String too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) throw new EncoderException("String too long: " + bytes.length);
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
