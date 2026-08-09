package com.habitrain.core.client.gui.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** 自绘控件的统一交互音；原生 Button 已自行播放声音，不应重复调用。 */
public final class MenuSounds {
    private MenuSounds() {}

    public static void playClick() {
        try {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        } catch (Throwable ignored) {
            // 菜单音效永远不应阻断配置操作。
        }
    }
}
