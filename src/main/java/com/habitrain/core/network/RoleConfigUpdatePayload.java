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

    // Preserve the existing registered-payload UTF contract. Vanilla's unknown-payload
    // fallback limit is not the bound used by Fabric's registered codec provider.
    private static final int MAX_JSON_LENGTH = 65_536;
    private static final int MAX_JSON_BYTES = MAX_JSON_LENGTH * 3;

    public static final Type<RoleConfigUpdatePayload> TYPE =
            new Type<>(HabiTrainCore.id("role_config_update"));
    public static final StreamCodec<FriendlyByteBuf, RoleConfigUpdatePayload> CODEC =
            StreamCodec.ofMember(RoleConfigUpdatePayload::write, RoleConfigUpdatePayload::new);

    private RoleConfigUpdatePayload(FriendlyByteBuf buf) {
        this(readJson(buf));
    }

    private static String readJson(FriendlyByteBuf buf) {
        int start = buf.readerIndex();
        int length = buf.readVarInt();
        if (length < 0 || length > MAX_JSON_BYTES || length > buf.readableBytes())
            throw new io.netty.handler.codec.DecoderException("Role config is oversized or truncated");
        buf.readerIndex(start);
        return buf.readUtf(MAX_JSON_LENGTH);
    }

    private void write(FriendlyByteBuf buf) {
        if (configJson != null && configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new io.netty.handler.codec.EncoderException("Role config exceeds C2S byte budget");
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
