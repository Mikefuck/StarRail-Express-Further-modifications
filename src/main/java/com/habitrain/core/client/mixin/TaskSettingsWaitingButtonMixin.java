/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen$WaitingMenuCellButton
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={LimitedInventoryScreen.WaitingMenuCellButton.class}, remap=false)
public abstract class TaskSettingsWaitingButtonMixin {
    private static final String TASK_SETTINGS_KEY = "screen.habitrain_core.task_settings";
    private static final String MAP_SETTINGS_KEY = "screen.habitrain_core.map_settings";

    @Inject(method={"renderWidget"}, at={@At(value="TAIL")}, remap=false)
    private void habitrain$renderDisabledState(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Button button = (Button) (Object) this;
        if (!button.visible || button.active || !habitrain$isProtectedSettingButton(button)) {
            return;
        }
        graphics.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), 0x70000000);
        graphics.renderOutline(button.getX(), button.getY(), button.getWidth(), button.getHeight(), -11908534);
    }

    private static boolean habitrain$isProtectedSettingButton(Button button) {
        Component label = button.getMessage();
        if (label == null) {
            return false;
        }
        ComponentContents contents = label.getContents();
        if (!(contents instanceof TranslatableContents translatable)) {
            return false;
        }
        String key = translatable.getKey();
        return TASK_SETTINGS_KEY.equals(key) || MAP_SETTINGS_KEY.equals(key);
    }
}
