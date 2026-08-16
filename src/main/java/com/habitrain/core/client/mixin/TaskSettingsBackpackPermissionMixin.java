/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen
 *  io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen$WaitingMenuCellButton
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.ComponentContents
 *  net.minecraft.network.chat.contents.TranslatableContents
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.menu.MenuPermissions;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={LimitedInventoryScreen.class})
public abstract class TaskSettingsBackpackPermissionMixin {
    private static final String TASK_SETTINGS_KEY = "screen.habitrain_core.task_settings";
    @Shadow
    public ArrayList<Button> menuSelections;
    @Shadow
    @Final
    private ArrayList<LimitedInventoryScreen.WaitingMenuCellButton> waitingMenuButtons;

    @Inject(method={"render"}, at={@At(value="TAIL")})
    private void habitrain$lockTaskSettingsForNonOp(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        this.habitrain$applyPermissionLock();
    }

    @Inject(method={"updateWaitingMenuVisibility"}, at={@At(value="TAIL")}, remap=false)
    private void habitrain$lockWaitingTaskSettingsForNonOp(CallbackInfo ci) {
        this.habitrain$applyPermissionLockToWaitingButtons();
    }

    private void habitrain$applyPermissionLock() {
        boolean allowed = MenuPermissions.canAccessTaskSettings();
        if (this.menuSelections != null) {
            for (Button button : this.menuSelections) {
                TaskSettingsBackpackPermissionMixin.habitrain$applyPermissionLock(button, allowed);
            }
        }
        this.habitrain$applyPermissionLockToWaitingButtons(allowed);
    }

    private void habitrain$applyPermissionLockToWaitingButtons() {
        this.habitrain$applyPermissionLockToWaitingButtons(MenuPermissions.canAccessTaskSettings());
    }

    private void habitrain$applyPermissionLockToWaitingButtons(boolean allowed) {
        if (this.waitingMenuButtons == null) {
            return;
        }
        for (Button button : this.waitingMenuButtons) {
            TaskSettingsBackpackPermissionMixin.habitrain$applyPermissionLock(button, allowed);
        }
    }

    private static void habitrain$applyPermissionLock(Button button, boolean allowed) {
        if (button != null && TaskSettingsBackpackPermissionMixin.habitrain$isTaskSettingsButton(button)) {
            button.active = allowed;
        }
    }

    private static boolean habitrain$isTaskSettingsButton(Button button) {
        TranslatableContents contents;
        ComponentContents componentContents;
        Component label = button.getMessage();
        return label != null && (componentContents = label.getContents()) instanceof TranslatableContents && TASK_SETTINGS_KEY.equals((contents = (TranslatableContents)componentContents).getKey());
    }
}
