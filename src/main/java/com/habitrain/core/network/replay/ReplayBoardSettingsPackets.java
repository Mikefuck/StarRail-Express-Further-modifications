package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Editing requests and acknowledgement; vanilla block-entity updates broadcast saved settings. */
public final class ReplayBoardSettingsPackets {
    private ReplayBoardSettingsPackets() {}

    public record Save(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_save"));
        public static final StreamCodec<FriendlyByteBuf, Save> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension);
            buf.writeBlockPos(value.pos);
            var s = value.settings;
            buf.writeFloat(s.mapSize()); buf.writeFloat(s.height());
            buf.writeFloat(s.offsetX()); buf.writeFloat(s.offsetZ()); buf.writeFloat(s.rotation());
        }, buf -> {
            ResourceLocation dimension = buf.readResourceLocation();
            BlockPos pos = buf.readBlockPos();
            ReplayBoardSettings settings = new ReplayBoardSettings(buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
            if (buf.isReadable()) throw new DecoderException("Trailing table settings bytes");
            return new Save(dimension, pos, settings);
        });
        @Override public Type<Save> type() { return TYPE; }
    }

    public record Result(ResourceLocation dimension, BlockPos pos, boolean saved) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_result"));
        public static final StreamCodec<FriendlyByteBuf, Result> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension); buf.writeBlockPos(value.pos); buf.writeBoolean(value.saved);
        }, buf -> {
            var result = new Result(buf.readResourceLocation(), buf.readBlockPos(), buf.readBoolean());
            if (buf.isReadable()) throw new DecoderException("Trailing table settings result bytes");
            return result;
        });
        @Override public Type<Result> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Save.TYPE, Save.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.TYPE, Result.CODEC);
    }
}
