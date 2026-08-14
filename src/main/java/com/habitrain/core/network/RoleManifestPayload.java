package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.role.config.RoleManifest;
import com.habitrain.core.role.config.RoleProviderManifest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S2C payload carrying the server's role-extension manifest (§14.2) so the client
 * can compare API version, required provider versions, capability set, gameplay
 * definition hash, snapshot ids and the current {@code roleExtensionsV2} config.
 * Broadcast at join and after every config change.
 */
public record RoleManifestPayload(
        String coreApiVersion,
        List<ProviderRow> providers,
        List<String> capabilities,
        String definitionHash,
        String lobbySnapshotId,
        @Nullable String roundSnapshotId,
        String presentationHash,
        String configJson) implements CustomPacketPayload {

    /** One provider row over the wire. */
    public record ProviderRow(String providerId, String version, boolean requiredClient) {
    }

    public static final Type<RoleManifestPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_manifest"));
    public static final StreamCodec<FriendlyByteBuf, RoleManifestPayload> CODEC =
            StreamCodec.ofMember(RoleManifestPayload::write, RoleManifestPayload::new);

    public static RoleManifestPayload fromManifest(RoleManifest manifest) {
        List<ProviderRow> rows = new ArrayList<>();
        for (RoleProviderManifest p : manifest.providers()) {
            rows.add(new ProviderRow(p.providerId(), p.version(), p.requiredClient()));
        }
        return new RoleManifestPayload(
                manifest.coreApiVersion(),
                rows,
                new ArrayList<>(manifest.capabilities()),
                manifest.definitionHash(),
                manifest.lobbySnapshotId(),
                manifest.roundSnapshotId(),
                manifest.presentationHash(),
                manifest.configJson());
    }

    /** Decodes back into the pure manifest data. */
    public RoleManifest toManifest() {
        List<RoleProviderManifest> rows = new ArrayList<>();
        for (ProviderRow row : providers) {
            rows.add(new RoleProviderManifest(row.providerId(), row.version(), row.requiredClient()));
        }
        return new RoleManifest(coreApiVersion, rows, Set.copyOf(capabilities),
                definitionHash, lobbySnapshotId, roundSnapshotId, presentationHash, configJson);
    }

    private RoleManifestPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64),
                readProviders(buf),
                readStrings(buf),
                buf.readUtf(128),
                buf.readUtf(128),
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readUtf(128),
                buf.readUtf(1 << 20));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(coreApiVersion == null ? "" : coreApiVersion, 64);
        buf.writeVarInt(providers == null ? 0 : providers.size());
        if (providers != null) {
            for (ProviderRow row : providers) {
                buf.writeUtf(row.providerId() == null ? "" : row.providerId(), 128);
                buf.writeUtf(row.version() == null ? "" : row.version(), 64);
                buf.writeBoolean(row.requiredClient());
            }
        }
        writeStrings(buf, capabilities);
        buf.writeUtf(definitionHash == null ? "" : definitionHash, 128);
        buf.writeUtf(lobbySnapshotId == null ? "" : lobbySnapshotId, 128);
        buf.writeBoolean(roundSnapshotId != null);
        if (roundSnapshotId != null) {
            buf.writeUtf(roundSnapshotId, 128);
        }
        buf.writeUtf(presentationHash == null ? "" : presentationHash, 128);
        buf.writeUtf(configJson == null ? "" : configJson, 1 << 20);
    }

    private static List<ProviderRow> readProviders(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<ProviderRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new ProviderRow(buf.readUtf(128), buf.readUtf(64), buf.readBoolean()));
        }
        return rows;
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(64));
        }
        return out;
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values == null ? 0 : values.size());
        if (values != null) {
            for (String value : values) {
                buf.writeUtf(value == null ? "" : value, 64);
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendTo(ServerPlayer player) {
        ServerPlayNetworking.send(player, fromManifest(com.habitrain.core.role.config.RoleManifestService.build()));
    }

    public static void broadcastToAll(net.minecraft.server.MinecraftServer server) {
        RoleManifestPayload payload = fromManifest(com.habitrain.core.role.config.RoleManifestService.build());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
