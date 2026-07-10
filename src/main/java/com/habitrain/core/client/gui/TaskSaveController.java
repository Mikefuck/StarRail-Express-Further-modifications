package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class TaskSaveController {
    private final TaskDefinition def;
    private final TaskConfigEntry cfg;
    private final boolean remoteEditable;

    public TaskSaveController(TaskDefinition def, TaskConfigEntry cfg, boolean remoteEditable) {
        this.def = def;
        this.cfg = cfg;
        this.remoteEditable = remoteEditable;
    }

    public void syncFields(EditBox goldField, EditBox emotionField, EditBox weightField, EditBox mapField) {
        syncFields(goldField, emotionField, weightField, null, mapField);
    }

    public void syncFields(EditBox goldField, EditBox emotionField, EditBox weightField,
                            EditBox shopPriceField, EditBox mapField) {
        String raw = mapField != null ? mapField.getValue() : "";
        cfg.enabledMaps = TaskMapFilterEditor.parseMapList(raw);
        parseNumFields(goldField, emotionField, weightField, shopPriceField);
    }

    private void parseNumFields(EditBox goldField, EditBox emotionField, EditBox weightField,
                                EditBox shopPriceField) {
        try {
            String v = goldField.getValue().trim();
            cfg.hasGoldReward = !v.isEmpty();
            cfg.goldReward = v.isEmpty() ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException ignored) {}
        try {
            String v = emotionField.getValue().trim();
            cfg.hasEmotionReward = !v.isEmpty();
            cfg.emotionReward = v.isEmpty() ? 0f : Float.parseFloat(v);
        } catch (NumberFormatException ignored) {}
        try {
            String v = weightField.getValue().trim();
            cfg.hasRefreshWeight = !v.isEmpty();
            cfg.refreshWeight = v.isEmpty() ? 0f : Float.parseFloat(v);
        } catch (NumberFormatException ignored) {}
        if (shopPriceField != null) {
            try {
                String v = shopPriceField.getValue().trim();
                cfg.hasShopPrice = !v.isEmpty();
                cfg.shopPrice = v.isEmpty() ? 0 : Math.max(0, Integer.parseInt(v));
            } catch (NumberFormatException ignored) {}
        }
    }

    public void resetDefault() {
        if (!remoteEditable) return;
        cfg.enabled = true;
        cfg.enabledMaps.clear();
        cfg.mapFilterMode = 0;
        cfg.instinctColor = 0xB4C8C8C8;
        cfg.outlineWidth = 4.0f;
        cfg.hasGoldReward = false;
        cfg.goldReward = 0;
        cfg.hasEmotionReward = false;
        cfg.emotionReward = 0f;
        cfg.hasRefreshWeight = false;
        cfg.refreshWeight = 0f;
        cfg.hasShopPrice = false;
        cfg.shopPrice = 0;
        saveCurrent();
    }

    public void saveCurrent() {
        if (!remoteEditable) return;
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
    }

    public static void showMessage(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(msg), true);
    }
}
