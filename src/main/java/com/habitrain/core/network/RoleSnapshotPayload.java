package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.DecoderException;

/**
 * S2C payload carrying the compiled role-extension entry view (with statuses,
 * enablement source, conflict fields) plus the current snapshot ids and config,
 * so the Mod Menu role-extension page can render the same rich list the server
 * diagnostics show (fix-doc §13.2). Broadcast at join and after config changes.
 */
public record RoleSnapshotPayload(
        List<EntryRow> entries,
        String lobbySnapshotId,
        @Nullable String roundSnapshotId,
        @Nullable String pendingSnapshotId,
        String definitionHash,
        String configJson) implements CustomPacketPayload {

    /** Compatibility constructor retained for code compiled before pending snapshots. */
    public RoleSnapshotPayload(List<EntryRow> entries, String lobbySnapshotId,
                               @Nullable String roundSnapshotId, String definitionHash,
                               String configJson) {
        this(entries, lobbySnapshotId, roundSnapshotId, null, definitionHash, configJson);
    }

    /** One compiled entry row over the wire. */
    public record EntryRow(
            String entryId,
            String providerId,
            String operation,
            String target,
            String status,
            @Nullable String statusMessage,
            @Nullable String enabledSource,
            @Nullable String conflictFields,
            @Nullable String definitionHash) {
    }

    public static final Type<RoleSnapshotPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_snapshot"));

    private static final int MAX_ENTRIES = 4096;

    public static final StreamCodec<FriendlyByteBuf, RoleSnapshotPayload> CODEC =
            StreamCodec.ofMember(RoleSnapshotPayload::write, RoleSnapshotPayload::new);

    private RoleSnapshotPayload(FriendlyByteBuf buf) {
        this(readEntries(buf),
                buf.readUtf(128),
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readUtf(128),
                buf.readUtf(1 << 20));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (EntryRow row : entries) {
                buf.writeUtf(row.entryId() == null ? "" : row.entryId(), 256);
                buf.writeUtf(row.providerId() == null ? "" : row.providerId(), 128);
                buf.writeUtf(row.operation() == null ? "" : row.operation(), 32);
                buf.writeUtf(row.target() == null ? "" : row.target(), 128);
                buf.writeUtf(row.status() == null ? "" : row.status(), 32);
                writeOpt(buf, row.statusMessage());
                writeOpt(buf, row.enabledSource());
                writeOpt(buf, row.conflictFields());
                writeOpt(buf, row.definitionHash());
            }
        }
        buf.writeUtf(lobbySnapshotId == null ? "" : lobbySnapshotId, 128);
        buf.writeBoolean(roundSnapshotId != null);
        if (roundSnapshotId != null) {
            buf.writeUtf(roundSnapshotId, 128);
        }
        buf.writeBoolean(pendingSnapshotId != null);
        if (pendingSnapshotId != null) {
            buf.writeUtf(pendingSnapshotId, 128);
        }
        buf.writeUtf(definitionHash == null ? "" : definitionHash, 128);
        buf.writeUtf(configJson == null ? "" : configJson, 1 << 20);
    }

    private static List<EntryRow> readEntries(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) {
            throw new DecoderException("Invalid snapshot entry count: " + n);
        }
        List<EntryRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new EntryRow(
                    buf.readUtf(256),
                    buf.readUtf(128),
                    buf.readUtf(32),
                    buf.readUtf(128),
                    buf.readUtf(32),
                    readOpt(buf),
                    readOpt(buf),
                    readOpt(buf),
                    readOpt(buf)));
        }
        return rows;
    }

    private static void writeOpt(FriendlyByteBuf buf, @Nullable String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeUtf(value, 256);
        }
    }

    private static @Nullable String readOpt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(256) : null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendTo(ServerPlayer player) {
        ServerPlayNetworking.send(player,
                com.habitrain.core.role.config.RoleSnapshotService.build());
    }

    public static void broadcastToAll(net.minecraft.server.MinecraftServer server) {
        RoleSnapshotPayload payload = com.habitrain.core.role.config.RoleSnapshotService.build();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
