/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  io.wifi.starrailexpress.client.gui.screen.ingame.GameMenuEntries
 *  io.wifi.starrailexpress.client.gui.screen.ingame.GameMenuEntries$MenuEntry
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.ComponentContents
 *  net.minecraft.network.chat.contents.TranslatableContents
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import io.wifi.starrailexpress.client.gui.screen.ingame.GameMenuEntries;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={GameMenuEntries.class}, remap=false)
public abstract class TaskSettingsMenuEntriesMixin {
    private static final String TASK_SETTINGS_KEY = "screen.habitrain_core.task_settings";

    @Inject(method={"entries"}, at={@At(value="RETURN")}, cancellable=true, remap=false)
    private static void habitrain$replaceInGameSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, CallbackInfoReturnable<ArrayList<GameMenuEntries.MenuEntry>> cir) {
        TaskSettingsMenuEntriesMixin.habitrain$replaceSettingsEntry(minecraft, parent, toggleViewMenu, (ArrayList)cir.getReturnValue());
    }

    @Inject(method={"entries_hub"}, at={@At(value="RETURN")}, cancellable=true, remap=false)
    private static void habitrain$replaceHubSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, CallbackInfoReturnable<ArrayList<GameMenuEntries.MenuEntry>> cir) {
        TaskSettingsMenuEntriesMixin.habitrain$replaceSettingsEntry(minecraft, parent, toggleViewMenu, (ArrayList)cir.getReturnValue());
    }

    private static void habitrain$replaceSettingsEntry(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, ArrayList<GameMenuEntries.MenuEntry> entries) {
        for (int i = 0; i < entries.size(); ++i) {
            GameMenuEntries.MenuEntry entry = entries.get(i);
            if (!TaskSettingsMenuEntriesMixin.habitrain$isUpstreamSettingsEntry(entry.label())) continue;
            entries.set(i, new GameMenuEntries.MenuEntry((Component)Component.translatable((String)TASK_SETTINGS_KEY), button -> TaskSettingsMenuEntriesMixin.habitrain$openTaskSettings(minecraft, parent, toggleViewMenu)));
            return;
        }
    }

    private static boolean habitrain$isUpstreamSettingsEntry(Component label) {
        ComponentContents componentContents = label.getContents();
        if (!(componentContents instanceof TranslatableContents)) {
            return false;
        }
        TranslatableContents contents = (TranslatableContents)componentContents;
        String key = contents.getKey();
        return "screen.limited_inventory.menu.mod_settings".equals(key) || "screen.limited_inventory.menu.mod_settings_client".equals(key);
    }

    private static void habitrain$openTaskSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu) {
        if (minecraft == null || minecraft.player == null || !minecraft.player.hasPermissions(2)) {
            return;
        }
        minecraft.setScreen((Screen)ConfigMenuScreen.openTaskSettings(parent));
        if (toggleViewMenu != null) {
            toggleViewMenu.accept(false);
        }
    }
}
