package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** A null target requests the authorized roster; a UUID selects an observation target. */
public record DollTailorActionPayload(UUID target) implements CustomPacketPayload {
    public static final Type<DollTailorActionPayload> TYPE = new Type<>(HabiTrainCore.id("doll_tailor_action"));
    public static final StreamCodec<FriendlyByteBuf, DollTailorActionPayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> { buf.writeNullable(value.target, (out, id) -> out.writeUUID(id)); },
            buf -> new DollTailorActionPayload(buf.readNullable(in -> in.readUUID())));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playC2S().register(TYPE, CODEC); }
}
