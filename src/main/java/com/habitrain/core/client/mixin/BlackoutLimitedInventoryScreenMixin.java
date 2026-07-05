package com.habitrain.core.client.mixin;

import com.habitrain.core.game.blackout.BlackoutShopService;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(LimitedInventoryScreen.class)
public abstract class BlackoutLimitedInventoryScreenMixin {

    @Inject(method = "getRoleShopEntries", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habiTrain$useBlackoutShopEntries(SRERole role, CallbackInfoReturnable<List<ShopEntry>> cir) {
        if (role == null) {
            return;
        }

        ResourceLocation roleId = role.getIdentifier();
        if (!BlackoutShopService.isBlackoutRole(roleId)) {
            return;
        }

        // 仅当停电模式为该角色定义了专属商店时才接管；否则回落 SRE 原版商店。
        // 新复用的 6 个 SRE 原版角色（guard/patroller/swast/elf/executioner/silencer/cleaner）
        // 没有在 BlackoutShopService 注册商店，应使用 SRE 原版 RoleShopHandler 提供的商店。
        if (BlackoutShopService.getDefinitions(roleId).isEmpty()) {
            return;
        }

        cir.setReturnValue(BlackoutShopService.getShopEntries(roleId));
    }
}
