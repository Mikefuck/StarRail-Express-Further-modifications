package com.habitrain.core.client.gui;

import com.habitrain.core.client.gui.config.ConfigRootScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成 - 提供配置界面（任务配置 / 小游戏 / 全局设置三 Tab）
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigRootScreen::new;
    }
}
