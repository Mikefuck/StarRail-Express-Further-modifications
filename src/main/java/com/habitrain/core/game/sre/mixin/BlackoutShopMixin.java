package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.blackout.BlackoutShopService;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SREPlayerShopComponent.class)
public abstract class BlackoutShopMixin {
    @Shadow @Final private Player player;

    @Inject(method = "getShopEntries", at = @At("HEAD"), cancellable = true, remap = false)
    private void habiTrain$provideBlackoutRoleShop(CallbackInfoReturnable<List<ShopEntry>> cir) {
        // getShopEntries 可能被客户端商店 UI 调用；SREGameWorldComponent 仅在服务端 level 有效，
        // 客户端 level 上访问会导致行为未定义，故直接放行原逻辑。
        if (player.level().isClientSide) {
            return;
        }
        var gameWorld = SREGameWorldComponent.KEY.get(player.level());
        if (gameWorld == null) {
            return;
        }

        var role = gameWorld.getRole(player);
        if (role == null) {
            return;
        }

        ResourceLocation roleId = role.getIdentifier();
        // 仅当停电模式为该角色定义了专属商店时才接管；否则回落 SRE 原版商店。
        if (!BlackoutShopService.hasBlackoutShop(roleId)) {
            return;
        }

        cir.setReturnValue(BlackoutShopService.getShopEntries(roleId));
    }
}
