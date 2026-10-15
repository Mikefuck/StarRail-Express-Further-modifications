package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.client.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Paginated, keyboard-accessible stock picker/list and explicit confirmation. */
@Environment(EnvType.CLIENT)
public final class RoleScreen extends Screen {
    private final @Nullable Screen parent;
    private final RoleScreenSession session;
    private final Consumer<RoleScreenResult> callback;
    private final BooleanSupplier stillAvailable;
    private int page;
    private int pageCount = 1;

    public RoleScreen(Component title, @Nullable Screen parent, RoleScreenSession session,
                      Consumer<RoleScreenResult> callback, BooleanSupplier stillAvailable) {
        super(title);
        this.parent = parent;
        this.session = session;
        this.callback = callback;
        this.stillAvailable = stillAvailable;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(320, width - 32);
        int left = (width - panelWidth) / 2;
        int rows = Math.max(1, (height - 110) / 24);
        pageCount = Math.max(1, (session.options().size() + rows - 1) / rows);
        page = Math.min(page, pageCount - 1);
        for (int i = page * rows; i < Math.min(session.options().size(), (page + 1) * rows); i++) {
            RoleScreenOption option = session.options().get(i);
            Component label = option.id().equals(session.selectedId())
                    ? Component.translatable("habitrain_core.role_screen.selected", option.label()) : option.label();
            addRenderableWidget(Button.builder(label, button -> {
                session.select(option.id());
                rebuildWidgets();
            }).bounds(left, 48 + (i % rows) * 24, panelWidth, 20).build());
        }
        if (pageCount > 1) {
            var previous = addRenderableWidget(Button.builder(Component.translatable("habitrain_core.role_screen.previous"),
                    button -> { page--; rebuildWidgets(); }).bounds(left, height - 54, 70, 20).build());
            previous.active = page > 0;
            var next = addRenderableWidget(Button.builder(Component.translatable("habitrain_core.role_screen.next"),
                    button -> { page++; rebuildWidgets(); }).bounds(left + panelWidth - 70, height - 54, 70, 20).build());
            next.active = page + 1 < pageCount;
        }
        var confirm = addRenderableWidget(Button.builder(Component.translatable("habitrain_core.role_screen.confirm"),
                button -> finish(session.confirm())).bounds(left, height - 28, (panelWidth - 8) / 2, 20).build());
        confirm.active = session.canConfirm();
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + (panelWidth + 8) / 2, height - 28, (panelWidth - 8) / 2, 20).build());
    }

    private void finish(@Nullable RoleScreenResult result) {
        if (result == null) return;
        // Completion precedes setScreen: removed() must not double-fire.
        if (minecraft != null) minecraft.setScreen(parent);
        deliver(result);
    }

    @Nullable Screen parentScreen() { return parent; }

    private void deliver(@Nullable RoleScreenResult result) {
        if (result == null) return;
        try { callback.accept(result); }
        catch (Throwable failure) { LoggerFactory.getLogger("RoleScreen").error("Role screen callback failed", failure); }
    }

    @Override public void onClose() { finish(session.cancel()); }
    @Override public void removed() { deliver(session.cancel()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (!stillAvailable.getAsBoolean()) onClose(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFFFF);
        if (session.kind() != RoleScreenKind.CONFIRM && session.options().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("habitrain_core.role_screen.empty"), width / 2, 52, 0xFFAAAAAA);
        }
        if (pageCount > 1) graphics.drawCenteredString(font,
                Component.translatable("habitrain_core.role_screen.page", page + 1, pageCount), width / 2, height - 48, 0xFFCCCCCC);
    }
}
