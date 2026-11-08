package com.habitrain.core.client.gui;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.network.LustActionPayload;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Server-authorized, expiring confirmation; closing it declines the sacrifice. */
public final class LustRevivalScreen extends Screen {
    private static UUID seenToken;
    private final UUID token;
    private final String targetName;
    private boolean answered;

    public LustRevivalScreen(UUID token, String name) {
        super(Component.translatable("screen.habitrain_core.sin_lust.revive_title"));
        this.token = token;
        targetName = name;
    }

    public static void clientTick(Minecraft client) {
        if (client.player == null || client.level == null) { seenToken = null; return; }
        var state = LustComponent.KEY.get(client.player);
        if (state.revivalToken() == null) { seenToken = null; return; }
        if (client.player.isSpectator() || !HabiRoles.isHabiRole(client.player, SevenSins.LUST)
                || SREGameWorldComponent.KEY.get(client.level).getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return;
        if (!state.revivalToken().equals(seenToken)) {
            UUID nextToken = state.revivalToken();
            String name = state.roster().stream().filter(e -> e.id().equals(state.selected()))
                    .map(LustComponent.CharmEntry::name).findFirst().orElse("?");
            LustRevivalScreen prompt = new LustRevivalScreen(nextToken, name);
            client.setScreen(prompt);
            if (client.screen == prompt) seenToken = nextToken;
        }
    }

    @Override protected void init() {
        int y = height / 2 + 35;
        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_core.sin_lust.revive_accept"),
                button -> answer(true)).bounds(width / 2 - 105, y, 102, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_core.sin_lust.revive_decline"),
                button -> answer(false)).bounds(width / 2 + 3, y, 102, 20).build());
    }

    private void answer(boolean accept) {
        if (!answered) {
            answered = true;
            if (minecraft != null && minecraft.getConnection() != null) {
                ClientPlayNetworking.send(new LustActionPayload(
                        accept ? LustActionPayload.REVIVE : LustActionPayload.DECLINE, token));
            }
        }
        if (minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(null);
        }
    }
    @Override public void onClose() { answer(false); }
    @Override public void removed() {
        try {
            if (!answered) {
                answered = true;
                if (minecraft != null && minecraft.getConnection() != null) {
                    ClientPlayNetworking.send(new LustActionPayload(LustActionPayload.DECLINE, token));
                }
            }
        } finally {
            super.removed();
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.isSpectator()) { minecraft.setScreen(null); return; }
        var state = LustComponent.KEY.get(minecraft.player);
        if (!token.equals(state.revivalToken()) || state.revivalTicksLeft() == 0
                || SREGameWorldComponent.KEY.get(minecraft.player.level()).getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) {
            minecraft.setScreen(null);
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;
        graphics.fill(cx - 145, cy - 65, cx + 145, cy + 65, 0xED231322);
        graphics.fill(cx - 145, cy - 65, cx + 145, cy - 63, 0xFFEF72B5);
        graphics.drawCenteredString(font, title, cx, cy - 47, 0xFFEF9BCC);
        graphics.drawCenteredString(font, Component.translatable("screen.habitrain_core.sin_lust.revive_target", targetName), cx, cy - 25, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.habitrain_core.sin_lust.revive_cost"), cx, cy - 7, 0xFFFFB4B4);
        long remaining = minecraft.player == null ? 0 : LustComponent.KEY.get(minecraft.player).revivalTicksLeft();
        graphics.drawCenteredString(font, Component.translatable("screen.habitrain_core.sin_lust.revive_time", (remaining + 19) / 20), cx, cy + 12, 0xFFF5D7EB);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
