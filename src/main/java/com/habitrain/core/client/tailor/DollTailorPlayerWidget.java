package com.habitrain.core.client.tailor;

import com.habitrain.core.game.sre.role.tailor.DollTailorRules;
import com.habitrain.core.network.DollTailorActionPayload;
import com.habitrain.core.network.DollTailorStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

/** Small cloth-backed portrait, with individual stitches marking a completed pattern. */
public final class DollTailorPlayerWidget extends Button {
    private final DollTailorStatePayload.Entry entry;
    private int lastProgress = -1;
    public DollTailorPlayerWidget(int x, int y, DollTailorStatePayload.Entry entry) {
        super(x - 5, y - 5, 26, 26, Component.literal(entry.name()), button -> {
            if (!DollTailorClient.casting() && ClientPlayNetworking.canSend(DollTailorActionPayload.TYPE))
                ClientPlayNetworking.send(new DollTailorActionPayload(entry.id()));
        }, DEFAULT_NARRATION);
        this.entry = entry;
    }
    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        var mc = Minecraft.getInstance();
        int progress = DollTailorClient.observed(entry.id());
        if (lastProgress != progress) {
            lastProgress = progress;
            Component label = Component.translatable("screen.habitrain_core.doll_tailor.target", entry.name(),
                    progress * 100 / DollTailorRules.OBSERVATION_TICKS);
            setMessage(label); setTooltip(Tooltip.create(label));
        }
        int x = getX(), y = getY();
        boolean ready = progress >= DollTailorRules.OBSERVATION_TICKS;
        boolean selected = entry.id().equals(DollTailorClient.state().selected());
        g.fill(x, y, x + 26, y + 26, selected ? 0xFF756052 : 0xFF362B35);
        g.fill(x + 2, y + 2, x + 24, y + 24, ready ? 0xFFEEE6D6 : 0xFF706676);
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(entry.id());
        PlayerFaceRenderer.draw(g, (info == null ? DefaultPlayerSkin.get(entry.id()) : info.getSkin()).texture(), x + 5, y + 5, 16);
        int stitch = ready ? 0xFFDABF89 : 0xFFB79CC9;
        for (int i = 3; i < 24; i += 5) {
            g.fill(x + i, y + 1, x + i + 2, y + 3, stitch);
            g.fill(x + i, y + 23, x + i + 2, y + 25, stitch);
            g.fill(x + 1, y + i, x + 3, y + i + 2, stitch);
            g.fill(x + 23, y + i, x + 25, y + i + 2, stitch);
        }
        g.fill(x + 3, y + 23, x + 3 + 20 * progress / DollTailorRules.OBSERVATION_TICKS, y + 25, 0xFFDABF89);
        if (isHoveredOrFocused() || selected) g.renderOutline(x - 1, y - 1, 28, 28, selected ? 0xFFDABF89 : 0xFFEEE6D6);
    }
}
