package com.habitrain.core.client.gui.menu;

import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TaskMapFilterEditor {
    public final Button mapFilterBtn;
    public final EditBox mapField;

    private final TaskConfigEntry cfg;
    private final boolean remoteEditable;
    private final Runnable onSave;

    public TaskMapFilterEditor(TaskConfigEntry cfg, boolean remoteEditable, Runnable onSave, Font font) {
        this.cfg = cfg;
        this.remoteEditable = remoteEditable;
        this.onSave = onSave;

        this.mapFilterBtn = Button.builder(
                Component.literal(getFilterModeLabel()), b -> cycleFilterMode()
        ).bounds(-10000, -10000, 90, 20).build();

        this.mapField = new EditBox(font, -10000, -10000, 180, 14, Component.literal(""));
        mapField.setMaxLength(512);
        String initMap = String.join(",", cfg.enabledMaps);
        if (!initMap.isEmpty()) mapField.setValue(initMap);
        mapField.setEditable(remoteEditable);
    }

    public int render(GuiGraphics g, Font f, int labelX, int rowX, int w, int y, int mx, int my, float delta) {
        boolean disabled = !cfg.enabled;

        int r1 = y;
        g.drawString(f, Component.literal("§7过滤模式:"), labelX, r1 + 4, 0xCCCCCC, false);
        mapFilterBtn.setX(rowX);
        mapFilterBtn.setY(r1);
        mapFilterBtn.active = !disabled;
        if (!disabled) {
            mapFilterBtn.render(g, mx, my, delta);
        } else {
            g.fill(rowX, r1, rowX + 90, r1 + 20, 0x33FFFFFF);
            g.drawString(f, Component.literal(getFilterModeLabel()).withStyle(style -> style.withColor(0x555555)),
                    rowX + 5, r1 + 6, 0, false);
        }

        String modeHint;
        if (disabled) {
            modeHint = "§7任务已禁用，地图设置不可用";
        } else if (cfg.mapFilterMode == 0) {
            modeHint = "§a✔ 所有地图都出现此任务";
        } else if (cfg.mapFilterMode == 1) {
            modeHint = "§e⚡ 仅以下列表中的地图出现此任务";
        } else {
            modeHint = "§c⛔ 以下列表中的地图§l不会§r出现此任务";
        }
        g.drawString(f, Component.literal(modeHint), rowX + 96, r1 + 4, 0, false);

        int r2 = r1 + 22;
        g.drawString(f, Component.literal("§7地图列表:"), labelX, r2 + 4, 0xCCCCCC, false);
        mapField.setX(rowX);
        mapField.setY(r2 + 4);
        mapField.setWidth(Math.max(40, Math.min(200, w - (rowX - labelX) - 40)));
        mapField.setEditable(!disabled && remoteEditable);
        mapField.render(g, mx, my, delta);

        if (disabled) {
            g.fill(mapField.getX(), mapField.getY(), mapField.getX() + mapField.getWidth(), mapField.getY() + 14, 0x22FFFFFF);
        }

        if (!disabled) {
            String hint;
            if (cfg.mapFilterMode == 0) {
                hint = "§7列表已忽略（当前为全部地图模式）";
            } else {
                hint = "§7逗号分隔多个地图名，如: map1,map2";
            }
            g.drawString(f, Component.literal(hint), rowX, r2 + 20, 0x777777, false);
        }

        return r2 + 28;
    }

    private String getFilterModeLabel() {
        return switch (cfg.mapFilterMode) {
            case 0 -> "§a全部地图";
            case 1 -> "§e白名单";
            case 2 -> "§c黑名单";
            default -> "§7未知";
        };
    }

    private void cycleFilterMode() {
        cfg.mapFilterMode = (cfg.mapFilterMode + 1) % 3;
        mapFilterBtn.setMessage(Component.literal(getFilterModeLabel()));
        onSave.run();
    }

    public boolean handleMouseClick(double mx, double my, int button) {
        if (cfg.enabled && mx >= mapFilterBtn.getX() && mx < mapFilterBtn.getX() + 90
                && my >= mapFilterBtn.getY() && my < mapFilterBtn.getY() + 20) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            mapFilterBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (cfg.enabled && mx >= mapField.getX() && mx < mapField.getX() + mapField.getWidth()
                && my >= mapField.getY() && my < mapField.getY() + 14) {
            if (!remoteEditable) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            mapField.setFocused(true);
            return true;
        }
        return false;
    }

    public void setActive(boolean active) {
        mapFilterBtn.active = active;
    }

    public static List<String> parseMapList(String v) {
        if (v == null || v.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(v.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
