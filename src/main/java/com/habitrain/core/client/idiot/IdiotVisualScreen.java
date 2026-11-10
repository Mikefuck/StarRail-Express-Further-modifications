package com.habitrain.core.client.idiot;

import com.habitrain.core.client.config.ClientVisualPreferences;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Native focus/narration and local, immediate-save preferences, accessible without OP. */
public final class IdiotVisualScreen extends Screen {
    private final Screen parent;
    private boolean failed;
    public IdiotVisualScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_core.idiot.visuals")); this.parent = parent;
    }
    @Override protected void init() {
        int w = Math.max(1, Math.min(340, width - 16)), x = (width - w) / 2;
        int top = Math.max(28, Math.min(64, (height - 114) / 2));
        Button first = null;
        for (int i = 0; i < 3; i++) {
            final int option = i;
            Button button = Button.builder(label(i), b -> {
                failed = !switch (option) {
                    case 0 -> ClientVisualPreferences.setIdiotFaceOverlay(!ClientVisualPreferences.isIdiotFaceOverlay());
                    case 1 -> ClientVisualPreferences.setIdiotParticles((ClientVisualPreferences.getIdiotParticles() + 1) % 3);
                    default -> ClientVisualPreferences.setIdiotLargeJackpot(!ClientVisualPreferences.isIdiotLargeJackpot());
                };
                b.setMessage(label(option));
                b.setTooltip(Tooltip.create(Component.translatable("screen.habitrain_core.idiot.local")));
            }).bounds(x, top + i * 24, w, 20).build();
            button.setTooltip(Tooltip.create(Component.translatable("screen.habitrain_core.idiot.local")));
            addRenderableWidget(button);
            if (first == null) first = button;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, height - 28, w, 20).build());
        setInitialFocus(first);
    }
    private Component label(int option) {
        String value = option == 1 ? "screen.habitrain_core.idiot.particles." + ClientVisualPreferences.getIdiotParticles()
                : (option == 0 ? ClientVisualPreferences.isIdiotFaceOverlay() : ClientVisualPreferences.isIdiotLargeJackpot())
                        ? "options.on" : "options.off";
        return Component.translatable("screen.habitrain_core.idiot.option." + option, Component.translatable(value));
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFDF70);
        var note = Component.translatable(failed ? "screen.habitrain_core.idiot.save_failed" : "screen.habitrain_core.idiot.local");
        var lines = font.split(note, Math.max(1, width - 20));
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            g.drawCenteredString(font, lines.get(i), width / 2, height - 54 + i * 10, failed ? 0xFFFF7777 : 0xFFBBBBBB);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
