package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.network.C2SRateLimiter;
import com.habitrain.core.vote.MapVoteProfileStore;
import io.wifi.starrailexpress.register.SREReceiverRegister;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SREReceiverRegister.class, remap = false)
public abstract class SREReceiverRegisterMixin {

    @Unique
    private static final long INTRO_PROFILE_COOLDOWN_MS = 3_000L;

    @Inject(method = "sendMapIntro", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$limitMapIntroBeforeDiskReads(ServerPlayer player, CallbackInfo ci) {
        if (player == null || player.hasDisconnected()
                || !C2SRateLimiter.tryAcquire(player.getUUID(), "map_intro_request", INTRO_PROFILE_COOLDOWN_MS)) ci.cancel();
    }

    @Inject(method = "sendMapIntro", at = @At("RETURN"), remap = false)
    private static void habitrain$syncMapVoteProfilesOnIntroRequest(ServerPlayer player, CallbackInfo ci) {
        if (player == null || player.server == null) {
            return;
        }
        if (!C2SRateLimiter.tryAcquire(player.getUUID(), "map_intro_profiles", INTRO_PROFILE_COOLDOWN_MS)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        if (overworld == null) {
            return;
        }
        try {
            var manager = ConfigManager.getInstance();
            var fragments = MapVoteProfileStore.loadProfileFragments(overworld,
                    manager.getModeMapVoteSettings().maps, manager.revision());
            for (var fragment : fragments) {
                ServerPlayNetworking.send(player, fragment);
            }
        } catch (Exception ignored) {
        }
    }
}
