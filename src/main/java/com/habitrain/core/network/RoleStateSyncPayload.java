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
 * decoding. A length {@code -1} encodes a "present but null" value, which is
 * distinct from a {@code removed=true} payload: the former replaces the slot
 * with an explicit {@code null}, the latter deletes the slot entirely (audit
 * P0-1: reset/round-end/world-unload must reach clients as removals, never as
 * stale values). {@code revision} is a server-side monotonic send counter used
 * by the client to pick the "latest" mirror deterministically (audit P1-5:
 * {@code dataVersion} is a schema version, not a time). {@code snapshot=true}
 * marks a payload that belongs to a permission-filtered full sync for one
 * player (review P2): the client drops stale mirrors on the first snapshot
 * payload of a batch.
 *
 * <p>The per-slot byte cap is enforced at encode time against the spec's
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
        @Nullable UUID ownerPlayerId,
        boolean removed,
        long revision,
        long snapshotId,
        boolean snapshotBegin,
        boolean snapshotEnd,
        boolean snapshot) implements CustomPacketPayload {

    public static final Type<RoleStateSyncPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_state_sync"));
    public static final StreamCodec<FriendlyByteBuf, RoleStateSyncPayload> CODEC =
            StreamCodec.ofMember(RoleStateSyncPayload::write, RoleStateSyncPayload::new);

    /** Hard decode cap; the spec's {@code maxSerializedBytes} is the real gate. */
    public static final int DECODE_MAX_BYTES = 65536;

    /** A value payload (never a removal). */
    public static RoleStateSyncPayload value(ResourceLocation id, RoleKey role, StateScope scope,
                                             @Nullable String worldKey, int dataVersion,
                                             @Nullable byte[] encoded, @Nullable UUID ownerPlayerId,
                                             long revision) {
        return new RoleStateSyncPayload(id, role, scope, worldKey, dataVersion, encoded,
                ownerPlayerId, false, revision, 0, false, false, false);
    }

    /**
     * A value payload that is part of a permission-filtered full snapshot for
     * one player (audit P0-2 / review P2): the client clears its mirror set on
     * the snapshot begin payload, so mirrors for slots the server no longer
     * holds (e.g. after the player stopped tracking and re-tracks) are dropped
     * instead of lingering.
     */
    public static RoleStateSyncPayload snapshotValue(ResourceLocation id, RoleKey role,
                                                     StateScope scope,
                                                     @Nullable String worldKey, int dataVersion,
                                                     @Nullable byte[] encoded,
                                                     @Nullable UUID ownerPlayerId, long revision,
                                                     long snapshotId) {
        return new RoleStateSyncPayload(id, role, scope, worldKey, dataVersion, encoded,
                ownerPlayerId, false, revision, snapshotId, false, false, true);
    }

    /** A removal payload: the slot no longer exists on the server. */
    public static RoleStateSyncPayload removed(ResourceLocation id, RoleKey role, StateScope scope,
                                               @Nullable String worldKey, int dataVersion,
                                               @Nullable UUID ownerPlayerId, long revision) {
        return new RoleStateSyncPayload(id, role, scope, worldKey, dataVersion, null,
                ownerPlayerId, true, revision, 0, false, false, false);
    }

    /** Marks the start of a full snapshot batch. */
    public static RoleStateSyncPayload snapshotBegin(long snapshotId, long revision) {
        return new RoleStateSyncPayload(null, null, null, null, 0, null, null,
                false, revision, snapshotId, true, false, false);
    }

    /** Marks the end of a full snapshot batch. */
    public static RoleStateSyncPayload snapshotEnd(long snapshotId, long revision) {
        return new RoleStateSyncPayload(null, null, null, null, 0, null, null,
                false, revision, snapshotId, false, true, false);
    }

    public RoleStateSyncPayload {
        encoded = encoded == null ? null : encoded.clone();
    }

    @Override
    public @Nullable byte[] encoded() {
        return encoded == null ? null : encoded.clone();
    }

    /** Whether the slot holds an explicit {@code null} value. */
    public boolean isNull() {
        return !removed && encoded == null;
    }

    private RoleStateSyncPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readResourceLocation() : null,
                buf.readBoolean() ? RoleKey.of(buf.readResourceLocation()) : null,
                buf.readBoolean() ? buf.readEnum(StateScope.class) : null,
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readVarInt(),
                readBody(buf),
                buf.readBoolean() ? buf.readUUID() : null,
                buf.readBoolean(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean());
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
        buf.writeBoolean(id != null);
        if (id != null) {
            buf.writeResourceLocation(id);
        }
        buf.writeBoolean(role != null);
        if (role != null) {
            buf.writeResourceLocation(role.location());
        }
        buf.writeBoolean(scope != null);
        if (scope != null) {
            buf.writeEnum(scope);
        }
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
        buf.writeBoolean(removed);
        buf.writeVarLong(revision);
        buf.writeVarLong(snapshotId);
        buf.writeBoolean(snapshotBegin);
        buf.writeBoolean(snapshotEnd);
        buf.writeBoolean(snapshot);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}