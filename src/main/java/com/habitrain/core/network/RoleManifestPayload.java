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
import java.util.List;
import java.util.Set;

import io.netty.handler.codec.DecoderException;

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
        String configJson,
        List<String> experimentalCapabilities) implements CustomPacketPayload {

    /** One provider row over the wire. */
    public record ProviderRow(String providerId, String version, boolean requiredClient) {
    }

    public static final Type<RoleManifestPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_manifest"));

    private static final int MAX_PROVIDERS = 4096;
    private static final int MAX_STRINGS = 4096;

    public RoleManifestPayload {
        providers = providers == null ? List.of() : List.copyOf(providers);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        experimentalCapabilities = experimentalCapabilities == null ? List.of() : List.copyOf(experimentalCapabilities);
        if (providers.size() > MAX_PROVIDERS || capabilities.size() > MAX_STRINGS
                || experimentalCapabilities.size() > MAX_STRINGS) {
            throw new IllegalArgumentException("Role manifest exceeds the supported declaration count");
        }
    }

    public static final StreamCodec<FriendlyByteBuf, RoleManifestPayload> CODEC =
            StreamCodec.ofMember(RoleManifestPayload::write, RoleManifestPayload::new);

    /**
     * Wire copy of a live manifest. Handshake uses hashes/providers/capabilities,
     * and the Mod Menu page reads {@code roleExtensionsV2} from
     * {@link RoleSnapshotPayload#configJson()} — so the duplicate config body is
     * omitted here to keep JOIN packets smaller. {@link #toManifest()} still
     * round-trips the (empty) field so the codec stays valid.
     */
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
                "",
                new ArrayList<>(manifest.experimentalCapabilities()));
    }

    /** Decodes back into the pure manifest data. */
    public RoleManifest toManifest() {
        List<RoleProviderManifest> rows = new ArrayList<>();
        for (ProviderRow row : providers) {
            rows.add(new RoleProviderManifest(row.providerId(), row.version(), row.requiredClient()));
        }
        return new RoleManifest(coreApiVersion, rows, Set.copyOf(capabilities),
                definitionHash, lobbySnapshotId, roundSnapshotId, presentationHash, configJson,
                Set.copyOf(experimentalCapabilities));
    }

    private RoleManifestPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64),
                readProviders(buf),
                readStrings(buf),
                buf.readUtf(128),
                buf.readUtf(128),
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readUtf(128),
                buf.readUtf(1 << 20),
                readStrings(buf));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(coreApiVersion == null ? "" : coreApiVersion, 64);
        int providerLimit = providers == null ? 0 : Math.min(providers.size(), MAX_PROVIDERS);
        buf.writeVarInt(providerLimit);
        if (providers != null) {
            for (int i = 0; i < providerLimit; i++) {
                ProviderRow row = providers.get(i);
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
        int expLimit = experimentalCapabilities == null ? 0 : Math.min(experimentalCapabilities.size(), MAX_STRINGS);
        buf.writeVarInt(expLimit);
        if (experimentalCapabilities != null) {
            for (int i = 0; i < expLimit; i++) {
                String cap = experimentalCapabilities.get(i);
                buf.writeUtf(cap == null ? "" : cap, 64);
            }
        }
    }

    private static List<ProviderRow> readProviders(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_PROVIDERS || n > buf.readableBytes() / 3) {
            throw new DecoderException("Invalid provider count: " + n);
        }
        List<ProviderRow> rows = new ArrayList<>(Math.min(n, 32));
        for (int i = 0; i < n; i++) {
            rows.add(new ProviderRow(buf.readUtf(128), buf.readUtf(64), buf.readBoolean()));
        }
        return rows;
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_STRINGS || n > buf.readableBytes()) {
            throw new DecoderException("Invalid string list size: " + n);
        }
        List<String> out = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(64));
        }
        return out;
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> values) {
        int limit = values == null ? 0 : Math.min(values.size(), MAX_STRINGS);
        buf.writeVarInt(limit);
        if (values != null) {
            for (int i = 0; i < limit; i++) {
                String value = values.get(i);
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
        RoleManifest manifest = com.habitrain.core.role.config.RoleManifestService.build();
        com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE.publishServerManifest(manifest);
        ServerPlayNetworking.send(player, fromManifest(manifest));
    }

    public static void broadcastToAll(net.minecraft.server.MinecraftServer server) {
        RoleManifest manifest = com.habitrain.core.role.config.RoleManifestService.build();
        com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE.publishServerManifest(manifest);
        RoleManifestPayload payload = fromManifest(manifest);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
