package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity.baseTick → updateInvisibilityStatus 会把没有 INVISIBILITY 药水的实体
 * setInvisible(false)。默剧杀手尸体依赖 setInvisible(true) 隐藏，必须在 tick 末尾重设。
 * 对齐 noelles SilenceTotemEntity 每 tick setInvisible(true) 的模式。
 */
@Mixin(value = PlayerBodyEntity.class, remap = false)
public class PlayerBodyEntityTickMixin {

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void habitrain$keepMimeHiddenBodyInvisible(CallbackInfo ci) {
        PlayerBodyEntity self = (PlayerBodyEntity) (Object) this;
        if (self.level().isClientSide) return;
        if (MimeKillerComponent.isBodyHidden(self.getUUID())) {
            self.setInvisible(true);
        }
    }
}
