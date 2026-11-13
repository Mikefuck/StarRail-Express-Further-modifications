package com.habitrain.core.scene.client.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Skin lookup for avatars. Online players use their live {@link PlayerInfo} skin; players who have
 * left are resolved once through the vanilla profile cache and {@code SkinManager}. Until a skin
 * arrives the deterministic default skin is returned, and the next frame picks the real one up
 * (hot refresh, no extra bookkeeping in the renderer). Game thread only.
 */
final class ReplayHeadSkins {
    private static final Map<UUID, ResourceLocation> RESOLVED = new HashMap<>();
    private static final Set<UUID> PENDING = new HashSet<>();
    private static long generation;

    private ReplayHeadSkins() {}

    static ResourceLocation texture(UUID id) {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null) return info.getSkin().texture();
        }
        ResourceLocation cached = RESOLVED.get(id);
        if (cached != null) return cached;
        if (PENDING.add(id)) request(client, id);
        return DefaultPlayerSkin.get(id).texture();
    }

    private static void request(Minecraft client, UUID id) {
        long token = generation;
        try {
            SkullBlockEntity.fetchGameProfile(id)
                    .thenCompose((Optional<GameProfile> profile) -> profile.isPresent()
                            ? client.getSkinManager().getOrLoad(profile.get())
                            : CompletableFuture.<PlayerSkin>completedFuture(null))
                    .whenComplete((skin, failure) -> client.execute(() -> {
                        if (token != generation) return;
                        if (skin != null) RESOLVED.put(id, skin.texture());
                        else RESOLVED.put(id, DefaultPlayerSkin.get(id).texture());
                    }));
        } catch (RuntimeException unavailable) {
            RESOLVED.put(id, DefaultPlayerSkin.get(id).texture());
        }
    }

    static void clear() {
        generation++;
        RESOLVED.clear();
        PENDING.clear();
    }
}
