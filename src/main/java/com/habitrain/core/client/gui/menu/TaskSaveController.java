package com.habitrain.core.client.gui.menu;

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
    private String validationError;

    public TaskSaveController(TaskDefinition def, TaskConfigEntry cfg, boolean remoteEditable) {
        this.def = def;
        this.cfg = cfg;
        this.remoteEditable = remoteEditable;
    }

    public void syncFields(EditBox goldField, EditBox emotionField, EditBox weightField, EditBox mapField) {
        try {
            var candidate = com.habitrain.core.config.TaskNumericDraft.parse(goldField.getValue(), emotionField.getValue(), weightField.getValue());
            var maps = TaskMapFilterEditor.parseMapList(mapField != null ? mapField.getValue() : "");
            candidate.apply(cfg); cfg.enabledMaps = maps; validationError = null;
        } catch (IllegalArgumentException error) { validationError = error.getMessage(); }
    }

    public void resetDefault() {
        if (!canEditNow()) return;
        validationError = null;
        cfg.enabled = true;
        cfg.enabledMaps.clear();
        cfg.mapFilterMode = 0;
        cfg.hasInstinctColor = false;
        cfg.instinctColorFromLegacyJson = false;
        cfg.instinctColor = def.getInstinctColorRGB();
        cfg.outlineWidth = 4.0f;
        cfg.hasGoldReward = false;
        cfg.goldReward = 0;
        cfg.hasEmotionReward = false;
        cfg.emotionReward = 0f;
        cfg.hasRefreshWeight = false;
        cfg.refreshWeight = 0f;
        saveCurrent();
    }

    public void saveCurrent() {
        if (!canEditNow()) return;
        if (validationError != null) { showMessage("§c" + validationError + "，未保存"); return; }
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
        com.habitrain.core.client.InstinctColorHelper.markDirty();
        com.habitrain.core.client.config.ClientConfigSubmission.show(ConfigManager.getInstance().save());
    }

    private boolean canEditNow() {
        if (remoteEditable && MenuPermissions.canEditRemoteConfigs()) return true;
        MenuPermissions.showDeniedMessage();
        return false;
    }

    public static void showMessage(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(msg), true);
    }
}
