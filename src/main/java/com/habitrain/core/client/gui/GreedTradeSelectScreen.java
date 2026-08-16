package com.habitrain.core.client.gui;

import com.habitrain.core.network.GreedTradeSelectPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Player selector opened from Greed's backpack. */
public final class GreedTradeSelectScreen extends Screen {
    private static final int PER_PAGE = 7;
    private final Screen parent;
    private int page;
    private List<AbstractClientPlayer> candidates = List.of();
    private List<AbstractClientPlayer> visibleCandidates = List.of();
    private String searchQuery = "";
    private EditBox searchBox;

    public GreedTradeSelectScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_core.greed_trade.select_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        candidates = minecraft == null || minecraft.level == null || minecraft.player == null
                ? List.of()
                : minecraft.level.players().stream()
                        .filter(player -> player != minecraft.player && !player.isSpectator())
                        .sorted(Comparator.comparing(player -> player.getGameProfile().getName()))
                        .toList();
        String q = searchQuery == null ? "" : searchQuery.toLowerCase(Locale.ROOT).trim();
        visibleCandidates = q.isEmpty() ? candidates
                : candidates.stream()
                        .filter(player -> player.getGameProfile().getName().toLowerCase(Locale.ROOT).contains(q))
                        .toList();

        searchBox = addRenderableWidget(new EditBox(font, width / 2 - 100, height / 2 - 76, 200, 18,
                Component.literal("搜索玩家名")));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchQuery == null ? "" : searchQuery);
        searchBox.setHint(Component.literal("搜索玩家名"));
        searchBox.setResponder(s -> {
            String next = s == null ? "" : s;
            if (next.equals(searchQuery)) {
                return;
            }
            searchQuery = next;
            rebuildWidgets();
            if (searchBox != null) {
                searchBox.setFocused(true);
                setFocused(searchBox);
                searchBox.setCursorPosition(searchQuery.length());
            }
        });

        int maxPage = Math.max(0, (visibleCandidates.size() - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        int from = page * PER_PAGE;
        int to = Math.min(visibleCandidates.size(), from + PER_PAGE);
        int y = height / 2 - 54;
        for (AbstractClientPlayer player : new ArrayList<>(visibleCandidates.subList(from, to))) {
            addRenderableWidget(Button.builder(Component.literal(player.getGameProfile().getName()), button -> {
                ClientPlayNetworking.send(new GreedTradeSelectPayload(player.getUUID()));
                if (minecraft != null) minecraft.setScreen(parent);
            }).bounds(width / 2 - 100, y, 200, 20).build());
            y += 22;
        }
        if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                page--;
                rebuildWidgets();
            }).bounds(width / 2 - 105, height / 2 + 104, 50, 20).build());
        }
        if (page < maxPage) {
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                page++;
                rebuildWidgets();
            }).bounds(width / 2 + 55, height / 2 + 104, 50, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 50, height / 2 + 104, 100, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fillGradient(width / 2 - 125, height / 2 - 108,
                width / 2 + 125, height / 2 + 132, 0xEE15120D, 0xEE2B2113);
        graphics.renderOutline(width / 2 - 125, height / 2 - 108, 250, 240, 0xFFD6A84B);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 98, 0xFFFFD977);
        if (visibleCandidates.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable(candidates.isEmpty()
                            ? "screen.habitrain_core.greed_trade.no_players"
                            : "screen.habitrain_core.greed_trade.no_match"),
                    width / 2, height / 2 - 5, 0xFFB8AD99);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
