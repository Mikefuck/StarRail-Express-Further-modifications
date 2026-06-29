package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutVotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 停电模式 — 投票选举警长 GUI
 *
 * 按 P 键打开, 30秒投票窗口,
 * 显示所有存活玩家, 点击选择 + 确认。
 */
public class VoteScreen extends Screen {

    private static final int ENTRY_H = 24;
    private static final int LIST_X = 60;
    private static final int LIST_W = 200;

    private UUID selectedTarget = null;
    private int windowRemaining = 30;
    private boolean confirmed = false;

    public VoteScreen() {
        super(Component.literal("§l投票选举警长"));
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(
                Component.literal("§a✔ 确认投票"),
                btn -> confirmVote()
        ).bounds(width / 2 - 50, height - 40, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        // 标题
        g.drawString(font, Component.literal("§l══════════ 投票选举警长 ══════════"),
                width / 2 - 90, 20, 0xFFFFFF, false);
        g.drawString(font, Component.literal("§7剩余: " + windowRemaining + " 秒"),
                width / 2 - 25, 36, 0xAAAAAA, false);

        // 玩家列表
        var players = Minecraft.getInstance().level.players();
        var self = Minecraft.getInstance().player;
        int y = 55;
        for (var player : players) {
            if (player.getUUID().equals(self.getUUID())) continue;

            boolean hovered = mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H;
            boolean selected = player.getUUID().equals(selectedTarget);

            if (selected) g.fill(LIST_X, y, LIST_X + LIST_W, y + ENTRY_H, 0x44333388);
            else if (hovered) g.fill(LIST_X, y, LIST_X + LIST_W, y + ENTRY_H, 0x22222255);

            String prefix = selected ? "§b● " : "§7○ ";
            g.drawString(font, Component.literal(prefix + player.getName().getString()),
                    LIST_X + 10, y + 7, selected ? 0x8888FF : 0xDDDDDD, false);

            y += ENTRY_H;
        }

        if (confirmed) {
            String msg = "§a✔ 已投票！等待结果...";
            g.drawString(font, Component.literal(msg),
                    width / 2 - font.width(msg) / 2, height / 2 + 50, 0, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;
        if (confirmed) return false;

        var players = Minecraft.getInstance().level.players();
        var self = Minecraft.getInstance().player;
        int y = 55;
        for (var player : players) {
            if (player.getUUID().equals(self.getUUID())) continue;
            if (mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H) {
                selectedTarget = player.getUUID();
                return true;
            }
            y += ENTRY_H;
        }
        return false;
    }

    private void confirmVote() {
        if (selectedTarget == null || confirmed) return;
        confirmed = true;
        ClientPlayNetworking.send(new BlackoutVotePayload(selectedTarget, false));
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }
}
