package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Synchronizes whether the local player may enter the eliminated-player rest area. */
public record EliminatedRestPromptPayload(boolean visible, boolean canToggle) implements CustomPacketPayload {

    public static final Type<EliminatedRestPromptPayload> TYPE =
            new Type<>(HabiTrainCore.id("eliminated_rest_prompt"));
    public static final StreamCodec<FriendlyByteBuf, EliminatedRestPromptPayload> CODEC =
            StreamCodec.ofMember(EliminatedRestPromptPayload::write, EliminatedRestPromptPayload::new);

    private EliminatedRestPromptPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(visible);
        buf.writeBoolean(canToggle);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendTo(ServerPlayer player, boolean visible, boolean canToggle) {
        if (player != null) {
            ServerPlayNetworking.send(player, new EliminatedRestPromptPayload(visible, canToggle));
        }
    }
}
