package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.StateScope;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * S2C payload carrying one synchronized role-state slot (fix-doc §10.4).
 *
 * <p>Fields are opaque bytes + metadata; the client mirrors them without
 * decoding. A length {@code -1} encodes a "present but null" value. The
 * per-slot byte cap is enforced at encode time against the spec's
 * {@code maxSerializedBytes}; this payload only enforces a hard decode cap
 * so a misbehaving peer cannot exhaust memory.
 */
public record RoleStateSyncPayload(
        ResourceLocation id,
        RoleKey role,
        StateScope scope,
        @Nullable String worldKey,
        int dataVersion,
        @Nullable byte[] encoded,
        @Nullable UUID ownerPlayerId) implements CustomPacketPayload {

    public static final Type<RoleStateSyncPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_state_sync"));
    public static final StreamCodec<FriendlyByteBuf, RoleStateSyncPayload> CODEC =
            StreamCodec.ofMember(RoleStateSyncPayload::write, RoleStateSyncPayload::new);

    /** Hard decode cap; the spec's {@code maxSerializedBytes} is the real gate. */
    public static final int DECODE_MAX_BYTES = 65536;

    public RoleStateSyncPayload {
        encoded = encoded == null ? null : encoded.clone();
    }

    @Override
    public @Nullable byte[] encoded() {
        return encoded == null ? null : encoded.clone();
    }

    /** Whether the slot holds an explicit {@code null} value. */
    public boolean isNull() {
        return encoded == null;
    }

    private RoleStateSyncPayload(FriendlyByteBuf buf) {
        this(buf.readResourceLocation(),
                RoleKey.of(buf.readResourceLocation()),
                buf.readEnum(StateScope.class),
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readVarInt(),
                readBody(buf),
                buf.readBoolean() ? buf.readUUID() : null);
    }

    private static @Nullable byte[] readBody(FriendlyByteBuf buf) {
        int len = buf.readVarInt();
        if (len < 0) {
            return null;
        }
        if (len > DECODE_MAX_BYTES) {
            throw new IllegalArgumentException("role-state slot exceeds decode cap: " + len);
        }
        byte[] body = new byte[len];
        buf.readBytes(body);
        return body;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        buf.writeResourceLocation(role.location());
        buf.writeEnum(scope);
        buf.writeBoolean(worldKey != null);
        if (worldKey != null) {
            buf.writeUtf(worldKey, 128);
        }
        buf.writeVarInt(dataVersion);
        if (encoded == null) {
            buf.writeVarInt(-1);
        } else {
            buf.writeVarInt(encoded.length);
            buf.writeBytes(encoded);
        }
        buf.writeBoolean(ownerPlayerId != null);
        if (ownerPlayerId != null) {
            buf.writeUUID(ownerPlayerId);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
