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
    private static final String MAP_SETTINGS_KEY = "screen.habitrain_core.map_settings";
    private static final String UPSTREAM_TASK_INSTINCT_CHOICES_KEY = "screen.limited_inventory.menu.task_instinct_choices";
    private static final String UPSTREAM_MAP_ROTATION_KEY = "screen.limited_inventory.menu.map_rotation";

    @Inject(method={"entries"}, at={@At(value="RETURN")}, cancellable=true, remap=false)
    private static void habitrain$replaceInGameSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, CallbackInfoReturnable<ArrayList<GameMenuEntries.MenuEntry>> cir) {
        ArrayList<GameMenuEntries.MenuEntry> entries = cir.getReturnValue();
        habitrain$removeTaskInstinctEntry(entries);
        habitrain$replaceSettingsEntry(minecraft, parent, toggleViewMenu, entries);
    }

    @Inject(method={"entries_hub"}, at={@At(value="RETURN")}, cancellable=true, remap=false)
    private static void habitrain$replaceHubSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, CallbackInfoReturnable<ArrayList<GameMenuEntries.MenuEntry>> cir) {
        ArrayList<GameMenuEntries.MenuEntry> entries = cir.getReturnValue();
        habitrain$removeTaskInstinctEntry(entries);
        habitrain$replaceMapRotationEntry(minecraft, parent, toggleViewMenu, entries);
        habitrain$replaceSettingsEntry(minecraft, parent, toggleViewMenu, entries);
    }

    private static void habitrain$removeTaskInstinctEntry(ArrayList<GameMenuEntries.MenuEntry> entries) {
        if (entries == null) return;
        entries.removeIf(entry -> habitrain$isKey(entry.label(), UPSTREAM_TASK_INSTINCT_CHOICES_KEY));
    }

    private static void habitrain$replaceMapRotationEntry(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, ArrayList<GameMenuEntries.MenuEntry> entries) {
        if (entries == null) return;
        for (int i = 0; i < entries.size(); ++i) {
            GameMenuEntries.MenuEntry entry = entries.get(i);
            if (!habitrain$isKey(entry.label(), UPSTREAM_MAP_ROTATION_KEY)) continue;
            entries.set(i, new GameMenuEntries.MenuEntry(
                    Component.translatable(MAP_SETTINGS_KEY),
                    button -> habitrain$openMapSettings(minecraft, parent, toggleViewMenu)
            ));
            return;
        }
    }

    private static void habitrain$replaceSettingsEntry(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu, ArrayList<GameMenuEntries.MenuEntry> entries) {
        if (entries == null) return;
        for (int i = 0; i < entries.size(); ++i) {
            GameMenuEntries.MenuEntry entry = entries.get(i);
            if (!habitrain$isUpstreamSettingsEntry(entry.label())) continue;
            entries.set(i, new GameMenuEntries.MenuEntry(
                    Component.translatable(TASK_SETTINGS_KEY),
                    button -> habitrain$openTaskSettings(minecraft, parent, toggleViewMenu)
            ));
            return;
        }
    }

    private static boolean habitrain$isKey(Component label, String targetKey) {
        if (label == null) return false;
        ComponentContents componentContents = label.getContents();
        if (!(componentContents instanceof TranslatableContents contents)) {
            return false;
        }
        return targetKey.equals(contents.getKey());
    }

    private static boolean habitrain$isUpstreamSettingsEntry(Component label) {
        if (label == null) return false;
        ComponentContents componentContents = label.getContents();
        if (!(componentContents instanceof TranslatableContents contents)) {
            return false;
        }
        String key = contents.getKey();
        return "screen.limited_inventory.menu.mod_settings".equals(key) || "screen.limited_inventory.menu.mod_settings_client".equals(key);
    }

    private static void habitrain$openTaskSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu) {
        if (minecraft == null || minecraft.player == null || !minecraft.player.hasPermissions(2)) {
            return;
        }
        minecraft.setScreen(ConfigMenuScreen.openTaskSettings(parent));
        if (toggleViewMenu != null) {
            toggleViewMenu.accept(false);
        }
    }

    private static void habitrain$openMapSettings(Minecraft minecraft, Screen parent, Consumer<Boolean> toggleViewMenu) {
        if (minecraft == null || minecraft.player == null || !minecraft.player.hasPermissions(2)) {
            return;
        }
        minecraft.setScreen(ConfigMenuScreen.openMapSettings(parent));
        if (toggleViewMenu != null) {
            toggleViewMenu.accept(false);
        }
    }
}
