package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.role.config.ClientManifest;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * C2S payload (audit P1-4): the client reports its local role-extension
 * manifest after receiving the server's {@link RoleManifestPayload}, so the
 * server can run the §14.2 handshake authoritatively and gate role actions for
 * clients that are missing required providers, on an incompatible API version,
 * or holding stale gameplay-definition hashes.
 */
public record RoleHandshakeReportPayload(
        String coreApiVersion,
        Map<String, String> localProviderVersions,
        boolean hasPresentationResources,
        @Nullable String expectedDefinitionHash,
        @Nullable String expectedPresentationHash,
        Set<String> localClientExtensionProviders) implements CustomPacketPayload {

    public static final Type<RoleHandshakeReportPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_handshake_report"));
    private static final int MAX_PROVIDERS = 1024;
    private static final int MAX_EXTENSION_PROVIDERS = 1024;
    public static final StreamCodec<FriendlyByteBuf, RoleHandshakeReportPayload> CODEC =
            StreamCodec.ofMember(RoleHandshakeReportPayload::write, RoleHandshakeReportPayload::new);

    public RoleHandshakeReportPayload {
        localProviderVersions = localProviderVersions == null
                ? Map.of() : Map.copyOf(localProviderVersions);
        localClientExtensionProviders = localClientExtensionProviders == null
                ? Set.of() : Set.copyOf(localClientExtensionProviders);
    }

    public static RoleHandshakeReportPayload fromClientManifest(ClientManifest manifest) {
        return new RoleHandshakeReportPayload(
                manifest.coreApiVersion(),
                manifest.localProviderVersions(),
                manifest.hasPresentationResources(),
                manifest.expectedDefinitionHash(),
                manifest.expectedPresentationHash(),
                manifest.localClientExtensionProviders());
    }

    /** Decodes back into the pure client-manifest data. */
    public ClientManifest toClientManifest() {
        return new ClientManifest(coreApiVersion, localProviderVersions,
                hasPresentationResources, expectedDefinitionHash, expectedPresentationHash,
                localClientExtensionProviders);
    }

    private RoleHandshakeReportPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64), readProviders(buf), buf.readBoolean(),
                buf.readBoolean() ? buf.readUtf(128) : null,
                buf.readBoolean() ? buf.readUtf(128) : null,
                readProviderIds(buf));
    }

    private static Map<String, String> readProviders(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_PROVIDERS) {
            throw new DecoderException("Invalid role provider count: " + count);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            out.put(buf.readUtf(128), buf.readUtf(64));
        }
        return out;
    }

    private static Set<String> readProviderIds(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_EXTENSION_PROVIDERS) {
            throw new DecoderException("Invalid client extension provider count: " + count);
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            out.add(buf.readUtf(128));
        }
        return Set.copyOf(out);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(coreApiVersion == null ? "" : coreApiVersion, 64);
        Map<String, String> versions = localProviderVersions == null ? Map.of() : localProviderVersions;
        buf.writeVarInt(versions.size());
        for (Map.Entry<String, String> e : versions.entrySet()) {
            buf.writeUtf(e.getKey() == null ? "" : e.getKey(), 128);
            buf.writeUtf(e.getValue() == null ? "" : e.getValue(), 64);
        }
        buf.writeBoolean(hasPresentationResources);
        buf.writeBoolean(expectedDefinitionHash != null);
        if (expectedDefinitionHash != null) {
            buf.writeUtf(expectedDefinitionHash, 128);
        }
        buf.writeBoolean(expectedPresentationHash != null);
        if (expectedPresentationHash != null) {
            buf.writeUtf(expectedPresentationHash, 128);
        }
        Set<String> loaded = localClientExtensionProviders == null ? Set.of() : localClientExtensionProviders;
        buf.writeVarInt(loaded.size());
        for (String id : loaded) {
            buf.writeUtf(id == null ? "" : id, 128);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
