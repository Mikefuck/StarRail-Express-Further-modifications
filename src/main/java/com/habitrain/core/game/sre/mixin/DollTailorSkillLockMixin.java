package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import io.wifi.starrailexpress.api.RoleSkill;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

@Mixin(value = RoleSkill.class, remap = false)
public abstract class DollTailorSkillLockMixin {
    @Inject(method = "beginUse(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;ILio/wifi/starrailexpress/api/RoleSkill$Phase;ZZ)Z",
            at = @At("HEAD"), cancellable = true)
    private static void habitrain$lockSkill(ServerPlayer player, UUID target, int slot, RoleSkill.Phase phase,
                                           boolean shifted, boolean possessed, CallbackInfoReturnable<Boolean> cir) {
        if (DollTailorService.isCasting(player)) cir.setReturnValue(false);
    }
}
