package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.vote.MapVoteProfileStore;
import io.wifi.starrailexpress.register.SREReceiverRegister;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SREReceiverRegister.class, remap = false)
public abstract class SREReceiverRegisterMixin {

    @Inject(method = "sendMapIntro", at = @At("RETURN"), remap = false)
    private static void habitrain$syncMapVoteProfilesOnIntroRequest(ServerPlayer player, CallbackInfo ci) {
        if (player != null && player.server != null) {
            ServerLevel overworld = player.server.overworld();
            if (overworld != null) {
                try {
                    var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                    var profiles = MapVoteProfileStore.loadProfiles(overworld, configMaps.keySet(), configMaps);
                    if (!profiles.isEmpty()) {
                        for (var fragment : MapVoteProfilePayload.fragment(profiles)) {
                            ServerPlayNetworking.send(player, fragment);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }
}
