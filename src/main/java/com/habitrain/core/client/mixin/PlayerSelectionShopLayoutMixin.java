package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.InventoryShopLayoutAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.agmas.noellesroles.client.PlayerPaginationHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Keep player heads, page labels and click targets together, including late roster updates. */
@Mixin(value = PlayerPaginationHelper.class, remap = false)
public abstract class PlayerSelectionShopLayoutMixin {
    @Shadow @Final private List<Button> managedButtons;
    @Shadow @Final private List<Button> managedPlayerWidgets;
    @Unique private int habitrain$appliedOffset;

    @Inject(method = "addPageWidgets", at = @At("HEAD"))
    private void habitrain$reservePlayerArea(Screen screen, CallbackInfo ci) {
        InventoryShopLayoutAccess.reserveRoleSelection(screen);
    }

    @WrapOperation(method = "addPageWidgets", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/screens/Screen;height:I", remap = true))
    private int habitrain$playerAreaCenter(Screen screen, Operation<Integer> original) {
        return original.call(screen) + 2 * InventoryShopLayoutAccess.offset(screen);
    }

    @Inject(method = "addPageWidgets", at = @At("TAIL"))
    private void habitrain$rememberPlayerOffset(Screen screen, CallbackInfo ci) {
        habitrain$appliedOffset = InventoryShopLayoutAccess.offset(screen);
    }

    @Inject(method = "drawPagination", at = @At("HEAD"))
    private void habitrain$moveExistingPlayers(GuiGraphics graphics, Screen screen, int centerY, CallbackInfo ci) {
        int offset = InventoryShopLayoutAccess.offset(screen);
        int delta = offset - habitrain$appliedOffset;
        if (delta == 0) return;
        for (Button widget : managedPlayerWidgets) widget.setY(widget.getY() + delta);
        for (Button button : managedButtons) button.setY(button.getY() + delta);
        habitrain$appliedOffset = offset;
    }

    @ModifyVariable(method = "drawPagination", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habitrain$playerPageLabel(int centerY, GuiGraphics graphics, Screen screen, int originalCenterY) {
        return InventoryShopLayoutAccess.centerY(screen);
    }
}
