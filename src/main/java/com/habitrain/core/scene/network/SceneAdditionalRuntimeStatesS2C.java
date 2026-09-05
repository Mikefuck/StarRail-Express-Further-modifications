package com.habitrain.core.scene.network;

import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** S2C state set for the non-default backgrounds of the active map. */
public record SceneAdditionalRuntimeStatesS2C(List<SceneRuntimeState> states)
        implements CustomPacketPayload {
    public static final Type<SceneAdditionalRuntimeStatesS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_additional_runtime_states"));
    private static final int MAX_STRING_LENGTH = 1_048_576;

    public SceneAdditionalRuntimeStatesS2C {
        states = states == null ? List.of() : List.copyOf(states);
        if (states.size() > SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP - 1) {
            throw new IllegalArgumentException("Too many additional scene runtime states");
        }
    }

    public static final StreamCodec<ByteBuf, SceneAdditionalRuntimeStatesS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAdditionalRuntimeStatesS2C decode(ByteBuf buf) {
            int count = buf.readUnsignedByte();
            if (count > SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP - 1) {
                throw new DecoderException("Too many additional scene runtime states: " + count);
            }
            List<SceneRuntimeState> states = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                boolean active = buf.readBoolean();
                long startTime = buf.readLong();
                int revision = buf.readInt();
                String assetKey = readString(buf);
                String assetHash = readString(buf);
                SceneProfile profile;
                try {
                    profile = SceneProfile.fromJson(JsonParser.parseString(readString(buf)).getAsJsonObject());
                } catch (RuntimeException ignored) {
                    profile = SceneProfile.createDefault();
                }
                states.add(new SceneRuntimeState(active, startTime, revision, assetKey, assetHash, profile));
            }
            return new SceneAdditionalRuntimeStatesS2C(states);
        }

        @Override
        public void encode(ByteBuf buf, SceneAdditionalRuntimeStatesS2C payload) {
            buf.writeByte(payload.states.size());
            for (SceneRuntimeState state : payload.states) {
                buf.writeBoolean(state.isActive());
                buf.writeLong(state.getStartGameTime());
                buf.writeInt(state.getProfileRevision());
                writeString(buf, state.getMapKey());
                writeString(buf, state.getAssetHash());
                writeString(buf, state.getProfile().toJson().toString());
            }
        }
    };

    private static String readString(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0 || length > MAX_STRING_LENGTH) throw new DecoderException("Invalid string length: " + length);
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) throw new EncoderException("String too long: " + bytes.length);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
