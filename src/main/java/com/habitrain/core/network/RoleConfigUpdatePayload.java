package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S payload carrying an OP-authored {@code roleExtensionsV2} config section
 * from the Mod Menu page (fix-doc §13.1). The server re-validates the sender
 * (OP4 + menu gate), applies, persists, recompiles the entry statuses and queues
 * a pending snapshot for the next round.
 */
public record RoleConfigUpdatePayload(String configJson) implements CustomPacketPayload {

    private static final int MAX_JSON_LENGTH = 1 << 20;

    public static final Type<RoleConfigUpdatePayload> TYPE =
            new Type<>(HabiTrainCore.id("role_config_update"));
    public static final StreamCodec<FriendlyByteBuf, RoleConfigUpdatePayload> CODEC =
            StreamCodec.ofMember(RoleConfigUpdatePayload::write, RoleConfigUpdatePayload::new);

    private RoleConfigUpdatePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(MAX_JSON_LENGTH));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(configJson == null ? "" : configJson, MAX_JSON_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
