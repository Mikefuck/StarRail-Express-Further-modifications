package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.RoleScreenKind;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Generic stock screen for v2 {@link RoleScreenSpec} (audit P1-2 / P1-5).
 *
 * <p>Providers that only need a simple player pick / confirm / list catalog can
 * rely on this screen; providers needing a fully custom layout still write
 * their own screen. The dispatcher opens it when a provider calls
 * {@link RoleClientExtensionHooks#openRoleScreen()} or a future action/command
 * triggers the same entry point.
 */
@Environment(EnvType.CLIENT)
public final class RoleScreen extends Screen {

    private final List<RoleScreenSpec> specs;

    public RoleScreen(Component title, List<RoleScreenSpec> specs) {
        super(title);
        this.specs = List.copyOf(specs);
    }

    @Override
    protected void init() {
        int y = height / 2 - (specs.size() * 24) / 2;
        for (RoleScreenSpec spec : specs) {
            String label = switch (spec.kind()) {
                case PLAYER_PICK -> "选择玩家";
                case CONFIRM -> "确认";
                case LIST -> "列表";
            };
            if (!spec.titleKey().isBlank()) {
                label = spec.titleKey();
            }
            int y0 = y;
            addRenderableWidget(Button.builder(Component.literal(label),
                            b -> onClose())
                    .bounds(width / 2 - 100, y0, 200, 20)
                    .build());
            y += 24;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFFFF);
    }
}
