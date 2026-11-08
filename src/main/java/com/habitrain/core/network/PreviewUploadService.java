package com.habitrain.core.network;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.internal.ServerFileTasks;
import com.habitrain.core.vote.MapVoteProfileStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;

/** Authorization and config changes stay on the server thread; PNG/index IO uses snapshots. */
public final class PreviewUploadService {
    private PreviewUploadService() {}
    private record Stored(MapVoteProfileStore.UploadResult result,
                          List<MapVoteProfilePayload> profiles) {}

    public static void enqueue(MinecraftServer server, ServerPlayer requester, MapVotePreviewUploadPayload payload) {
        var manager = ConfigManager.getInstance();
        var entry = manager.getModeMapVoteSettings().maps.get(payload.mapId());
        if (entry == null) return;
        var io = MapVoteProfileStore.prepareUpload(requester.serverLevel(), payload.mapId(), true);
        String path = MapVoteProfileStore.PREVIEW_DIR + "/" + MapVoteProfileStore.escapeId(payload.mapId()) + ".png";
        var previousProfile = entry.profile;
        String previous = previousProfile == null ? "" : previousProfile.previewPath;
        MapVoteEntry snapshot = MapVoteEntry.fromJson(entry.toJson());
        if (snapshot.profile == null) snapshot.profile = new com.habitrain.core.config.MapVoteProfileSettings();
        snapshot.profile.previewPath = path;
        byte[] bytes = payload.pngBytes();
        boolean queued = ServerFileTasks.submit(server, () -> {
            // A legacy custom image can acquire another map's reference while this job is queued.
            // Replace the deterministic target only; retain legacy files instead of deleting from a stale snapshot.
            var result = MapVoteProfileStore.saveUploadedPreview(io, payload.mapId(), "", bytes);
            var profiles = result.success() ? MapVoteProfilePayload.fragment(MapVoteProfileStore.loadProfiles(
                    io.base(), List.of(payload.mapId()), Map.of(payload.mapId(), snapshot))) : List.<MapVoteProfilePayload>of();
            return new Stored(result, profiles);
        }, (stored, failure) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(requester.getUUID());
            if (player != requester || player.hasDisconnected()) return;
            if (failure != null || !stored.result().success()) {
                player.sendSystemMessage(Component.translatable("message.habitrain_core.preview.failed", failure == null ? Component.literal(stored.result().message()) : Component.translatable("message.habitrain_core.preview.io_failed")));
                return;
            }
            if (!player.hasPermissions(2) || manager.getModeMapVoteSettings().maps.get(payload.mapId()) != entry
                    || entry.profile != previousProfile
                    || previousProfile != null && !java.util.Objects.equals(previousProfile.previewPath, previous)) {
                player.sendSystemMessage(Component.translatable("message.habitrain_core.preview.changed"));
                return;
            }
            if (!manager.saveMapVotePreviewPath(payload.mapId(), path).successful()) {
                player.sendSystemMessage(Component.translatable("message.habitrain_core.preview.save_failed"));
                return;
            }
            player.sendSystemMessage(Component.translatable("message.habitrain_core.preview.saved", payload.mapId()));
            for (ServerPlayer recipient : server.getPlayerList().getPlayers())
                for (var profile : stored.profiles()) ServerPlayNetworking.send(recipient, profile);
        });
        requester.sendSystemMessage(Component.translatable(queued ? "message.habitrain_core.preview.saving" : "message.habitrain_core.preview.busy"));
    }
}
