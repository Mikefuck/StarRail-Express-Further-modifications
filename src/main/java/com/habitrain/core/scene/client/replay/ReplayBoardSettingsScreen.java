package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Non-pausing editor with a local world preview; only Save writes to the server. */
public final class ReplayBoardSettingsScreen extends Screen {
    private static final String[] KEYS = {"size", "height", "offset_x", "offset_z", "rotation"};
    private static final float[] MIN = {1, 0, -16, -16, -180}, MAX = {32, 16, 16, 16, 180};
    private final ResourceLocation dimension;
    private final BlockPos pos;
    private final String[] values = new String[5];
    private final EditBox[] fields = new EditBox[5];
    private final List<Button> stepButtons = new ArrayList<>();
    private Button save;
    private boolean pending;
    private Component status;
    private int left, top, panelWidth, panelHeight, rowStep;

    public ReplayBoardSettingsScreen(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings) {
        super(tr("title"));
        this.dimension = dimension;
        this.pos = pos.immutable();
        setValues(settings);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("habitrain_core.replay_board.settings." + key, args);
    }

    private static String number(float value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void setValues(ReplayBoardSettings s) {
        float[] numbers = {s.mapSize(), s.height(), s.offsetX(), s.offsetZ(), s.rotation()};
        for (int i = 0; i < values.length; i++) values[i] = number(numbers[i]);
    }

    @Override protected void init() {
        panelWidth = Math.min(344, width - 16);
        panelHeight = Math.min(268, height - 12);
        left = width > 580 ? width - panelWidth - 12 : (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rowStep = Math.min(32, (panelHeight - 104) / 5);
        stepButtons.clear();
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            int y = top + 42 + i * rowStep, x = left + panelWidth - 117;
            EditBox field = new EditBox(font, x, y, 64, 20, tr(KEYS[i]));
            field.setMaxLength(12);
            field.setFilter(text -> text.matches("-?\\d*(\\.\\d*)?"));
            field.setValue(values[i]);
            field.setTooltip(Tooltip.create(tr("range", number(MIN[i]), number(MAX[i]), i == 4 ? "15°" : "0.25")));
            fields[i] = addRenderableWidget(field);
            field.setResponder(text -> {
                values[index] = text;
                status = null;
                refresh();
            });
            stepButtons.add(addRenderableWidget(Button.builder(Component.literal("−"), button -> step(index, -1))
                    .bounds(x - 23, y, 20, 20).build()));
            stepButtons.add(addRenderableWidget(Button.builder(Component.literal("+"), button -> step(index, 1))
                    .bounds(x + 67, y, 20, 20).build()));
        }
        int gap = 4, bw = (panelWidth - 24 - gap * 3) / 4, by = top + panelHeight - 30;
        stepButtons.add(addRenderableWidget(Button.builder(tr("reset"), button -> {
            setValues(ReplayBoardSettings.DEFAULT);
            for (int i = 0; i < fields.length; i++) fields[i].setValue(values[i]);
        }).bounds(left + 12, by, bw, 20).build()));
        addRenderableWidget(Button.builder(roofLabel(), button -> {
            ReplayBoardLocalState.toggleRoof();
            button.setMessage(roofLabel());
        }).bounds(left + 12 + bw + gap, by, bw, 20).build());
        save = addRenderableWidget(Button.builder(tr("save"), button -> save())
                .bounds(left + 12 + (bw + gap) * 2, by, bw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + 12 + (bw + gap) * 3, by, bw, 20).build());
        refresh();
    }

    private Component roofLabel() { return tr(ReplayBoardLocalState.fullBody() ? "roof_full" : "roof_cut"); }

    private void step(int index, int direction) {
        float current;
        try { current = Float.parseFloat(values[index]); }
        catch (NumberFormatException invalid) { current = index == 0 ? 6.25f : 0; }
        float next = Math.clamp(current + direction * (index == 4 ? 15f : 0.25f), MIN[index], MAX[index]);
        fields[index].setValue(number(next));
    }

    private @Nullable ReplayBoardSettings draft() {
        try {
            return new ReplayBoardSettings(Float.parseFloat(values[0]), Float.parseFloat(values[1]),
                    Float.parseFloat(values[2]), Float.parseFloat(values[3]), Float.parseFloat(values[4]));
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private void refresh() {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) continue;
            boolean valid = false;
            try {
                float value = Float.parseFloat(values[i]);
                valid = Float.isFinite(value) && value >= MIN[i] && value <= MAX[i];
            } catch (NumberFormatException ignored) {}
            fields[i].setTextColor(valid ? 0xFFEAD9B6 : 0xFFFF8888);
            fields[i].setEditable(!pending);
        }
        for (Button button : stepButtons) button.active = !pending;
        if (save != null) save.active = !pending && draft() != null && ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.Save.TYPE);
    }

    private void save() {
        ReplayBoardSettings settings = draft();
        if (pending || settings == null || !ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.Save.TYPE)) return;
        pending = true;
        status = tr("saving");
        refresh();
        ClientPlayNetworking.send(new ReplayBoardSettingsPackets.Save(dimension, pos, settings));
    }

    public void accept(ReplayBoardSettingsPackets.Result result) {
        if (!pending || !dimension.equals(result.dimension()) || !pos.equals(result.pos())) return;
        pending = false;
        if (result.saved()) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(tr("saved"), true);
            onClose();
        } else {
            status = tr("rejected");
            refresh();
        }
    }

    /** Renderer reads this draft instead of changing the synchronized block entity. */
    public static @Nullable ReplayBoardSettings previewAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ReplayBoardSettingsScreen screen && screen.pos.equals(pos)
                && mc.level != null && screen.dimension.equals(mc.level.dimension().location())) return screen.draft();
        return null;
    }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isCreative()
                || !minecraft.player.isAlive() || !minecraft.player.mayBuild()
                || !dimension.equals(minecraft.level.dimension().location())
                || !minecraft.player.canInteractWithBlock(pos, 1.0) || !minecraft.level.hasChunkAt(pos)
                || !minecraft.level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)) onClose();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(left, top, left + panelWidth, top + panelHeight, 0xF21C1815);
        g.fill(left, top, left + panelWidth, top + 2, 0xFFD0AB66);
        g.drawString(font, title, left + 12, top + 12, 0xFFEED9AE, false);
        g.drawString(font, tr("preview"), left + 12, top + 27, 0xFFBFAE91, false);
        for (int i = 0; i < fields.length; i++) {
            g.drawString(font, tr(KEYS[i]), left + 12, top + 48 + i * rowStep, 0xFFEAD9B6, false);
        }
        int hintY = top + panelHeight - 63;
        g.drawString(font, tr("hint"), left + 12, hintY, 0xFFBFAE91, false);
        Component message = status != null ? status : draft() == null ? tr("invalid") : tr("hint_offsets");
        g.drawString(font, message, left + 12, hintY + 13, draft() == null ? 0xFFFF8888 : 0xFFBFAE91, false);
        super.render(g, mouseX, mouseY, delta);
    }
}
