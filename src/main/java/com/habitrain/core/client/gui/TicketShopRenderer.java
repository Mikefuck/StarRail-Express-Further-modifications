package com.habitrain.core.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import io.wifi.starrailexpress.cca.DynamicShopComponent;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen.StoreItemWidget;
import io.wifi.starrailexpress.shop.client.ShopPriceClientCache;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Ticket artwork around the upstream shop buttons, keeping their original purchase callbacks and indices. */
public final class TicketShopRenderer {
    public interface ScreenAccess {
        boolean habitrain$renderShopItem(StoreItemWidget widget, GuiGraphics graphics, int mouseX, int mouseY);
    }

    private final Minecraft mc = Minecraft.getInstance();
    private TicketShopLayout layout;
    private List<ShopEntry> liveEntries = List.of();
    private StoreItemWidget hovered;
    private ShopEntry hoveredEntry;
    private Price hoveredPrice;
    private boolean pointerBlocked;

    public void layout(TicketShopLayout layout, List<StoreItemWidget> widgets, Button previous, Button next) {
        this.layout = layout;
        hovered = null;
        for (int i = 0; i < widgets.size(); i++) {
            StoreItemWidget widget = widgets.get(i);
            widget.visible = i >= layout.start() && i < layout.end();
            widget.active = widget.visible;
            widget.setWidth(layout.cardWidth());
            widget.setHeight(layout.cardHeight());
            if (widget.visible) widget.setPosition(layout.cardX(i), layout.cardY(i));
        }
        int center = layout.left() + layout.width() / 2;
        int navOffset = Math.round(28 * layout.scale());
        positionPageButton(previous, center - navOffset - layout.navSize(), layout.page() > 0);
        positionPageButton(next, center + navOffset, layout.page() + 1 < layout.pages());
    }

    public void clearLayout() {
        layout = null;
        hovered = null;
    }

    public boolean contains(double mouseX, double mouseY) {
        return layout != null && layout.contains(mouseX, mouseY);
    }

    private void positionPageButton(Button button, int x, boolean enabled) {
        if (button == null) return;
        button.setPosition(x, layout.navY());
        button.setWidth(layout.navSize());
        button.setHeight(layout.navSize());
        button.visible = layout.pages() > 1;
        button.active = button.visible && enabled;
    }

    public void beginFrame(List<ShopEntry> liveEntries, boolean pointerBlocked) {
        this.liveEntries = liveEntries;
        this.pointerBlocked = pointerBlocked;
        hovered = null;
    }

    public void renderPanel(GuiGraphics g) {
        if (layout == null || layout.count() == 0) return;
        TicketArt.Palette p = TicketArt.SAPPHIRE;
        float x = layout.left();
        float y = layout.top();
        float w = layout.width();
        float h = layout.height();
        float s = layout.scale();
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(RailArt.chamfer(x, y, w, h, 4 * s), y, y + h, p.paperTop(), p.paperBottom());
        geo.outline(RailArt.chamfer(x + s, y + s, w - 2 * s, h - 2 * s, 3 * s), 0.6f * s, p.trim());
        float ruleY = y + layout.header() - 4 * s;
        geo.line(x + 6 * s, ruleY, x + w - 6 * s, ruleY, 0.5f * s, RailArt.a(p.trim(), 0.6f));
        geo.end();
        Component title = Component.translatable("inventory.habitrain_core.ticket.shop");
        float titleScale = fitText(title, 0.85f * s, w - 12 * s);
        TicketArt.drawCentered(g, mc.font, title, x + w / 2, y + 5 * s, titleScale, p.bandInk());
        Component footer = layout.pages() > 1
                ? Component.literal((layout.page() + 1) + " / " + layout.pages())
                : Component.translatable("inventory.habitrain_core.ticket.shop.buy");
        float footerScale = fitText(footer, 0.7f * s, w - 12 * s);
        TicketArt.drawCentered(g, mc.font, footer, x + w / 2,
                layout.navY() + (layout.navSize() - mc.font.lineHeight * footerScale) / 2,
                footerScale, p.inkSoft());
        g.flush();
        RenderSystem.enableDepthTest();
    }

    public void renderItem(StoreItemWidget widget, GuiGraphics g, int mouseX, int mouseY) {
        ShopEntry entry = widget.index >= 0 && widget.index < liveEntries.size()
                ? liveEntries.get(widget.index) : widget.entry;
        Price price = priceOf(widget.index, entry);
        boolean selected = !pointerBlocked && widget.isHoveredOrFocused();
        if (selected) {
            hovered = widget;
            hoveredEntry = entry;
            hoveredPrice = price;
        }
        float s = widget.getWidth() / 44.0f;
        float w = widget.getWidth() / s;
        float h = widget.getHeight() / s;
        g.pose().pushPose();
        g.pose().translate(widget.getX(), widget.getY(), 0);
        g.pose().scale(s, s, 1);
        TicketArt.Palette p = TicketArt.SAPPHIRE;
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(RailArt.chamfer(0, 0, w, h, 3), 0, h,
                selected ? 0xFF355591 : 0xFF29477F, p.paperBottom());
        geo.outline(RailArt.chamfer(0.7f, 0.7f, w - 1.4f, h - 1.4f, 2.5f),
                selected ? 1.1f : 0.55f, selected ? RailArt.BRASS_LIGHT : RailArt.a(p.trim(), 0.72f));
        // The price is printed on the ticket, instead of an always-visible black tooltip.
        for (float dx = 4; dx < w - 4; dx += 4) {
            geo.line(dx, h - 13, Math.min(w - 4, dx + 2), h - 13, 0.5f, RailArt.a(p.trim(), 0.65f));
        }
        geo.end();
        Font font = mc.font;
        String name = entry.stack().getHoverName().getString();
        float nameScale = 0.65f;
        int nameWidth = (int) ((w - 6) / nameScale);
        if (font.width(name) > nameWidth) name = font.plainSubstrByWidth(name, nameWidth - font.width("…")) + "…";
        TicketArt.drawCentered(g, font, Component.literal(name), w / 2, 3, nameScale, p.ink());
        int itemX = Math.round((w - 16) / 2);
        g.renderItem(entry.stack(), itemX, 12);
        g.renderItemDecorations(font, entry.stack(), itemX, 12);
        Component amount = Component.literal(price.effective() + "\uE781");
        int ink = price.effective() < price.base() ? 0xFF9FE0BB
                : price.effective() > price.base() ? 0xFFFFA594 : RailArt.BRASS_LIGHT;
        TicketArt.drawCentered(g, font, amount, w / 2, h - 10,
                fitText(amount, 0.85f, w - 6), ink);
        g.flush();
        g.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    private Price priceOf(int index, ShopEntry entry) {
        int base = ShopPriceClientCache.overrideBasePrice(index, entry.stack(), entry.price());
        int effective = mc.player == null ? base : DynamicShopComponent.KEY.get(mc.player).effectivePrice(
                BuiltInRegistries.ITEM.getKey(entry.stack().getItem()), base);
        return new Price(base, effective);
    }

    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (hovered == null || pointerBlocked) return;
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, hoveredEntry.stack()));
        lines.add(Component.translatable("inventory.habitrain_core.ticket.shop.price", hoveredPrice.effective())
                .withStyle(ChatFormatting.GOLD));
        if (hoveredPrice.base() > 0 && hoveredPrice.effective() != hoveredPrice.base()) {
            boolean discounted = hoveredPrice.effective() < hoveredPrice.base();
            int percent = (int) ((double) hoveredPrice.effective() / hoveredPrice.base() * 100);
            lines.add(Component.translatable(discounted ? "gui.starrailexpress.shop.discount"
                    : "gui.starrailexpress.shop.increase", discounted ? 100 - percent : percent - 100)
                    .withStyle(discounted ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        int x = hovered.isHovered() ? mouseX : hovered.getX() + hovered.getWidth() / 2;
        int y = hovered.isHovered() ? mouseY : hovered.getY();
        g.renderTooltip(mc.font, lines, hoveredEntry.stack().getTooltipImage(), x, y);
    }

    private float fitText(Component text, float preferred, float available) {
        return Math.min(preferred, available / Math.max(1, mc.font.width(text)));
    }

    private record Price(int base, int effective) { }

    public static final class PageButton extends Button {
        private final int direction;
        private final BooleanSupplier themed;

        public PageButton(Button source, int direction, BooleanSupplier themed) {
            super(0, 0, 20, 20, source.getMessage(), b -> source.onPress(), DEFAULT_NARRATION);
            this.direction = direction;
            this.themed = themed;
            setTooltip(Tooltip.create(Component.translatable("inventory.habitrain_core.ticket.shop."
                    + (direction < 0 ? "previous" : "next"))));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            if (!themed.getAsBoolean()) {
                super.renderWidget(g, mouseX, mouseY, delta);
                return;
            }
            float x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int ink = active ? RailArt.BRASS_LIGHT : RailArt.a(RailArt.TEXT_FAINT, 0.5f);
            GuiGeo geo = GuiGeo.begin(g);
            geo.convexV(RailArt.chamfer(x, y, w, h, Math.min(2, h / 4)), y, y + h,
                    active && isHoveredOrFocused() ? 0xFF355591 : 0xFF243D78, 0xFF132352);
            geo.outline(RailArt.chamfer(x, y, w, h, Math.min(2, h / 4)), 0.6f, ink);
            float cx = x + w / 2, cy = y + h / 2;
            geo.tri(cx + direction * w * 0.18f, cy, ink,
                    cx - direction * w * 0.14f, cy - h * 0.22f, ink,
                    cx - direction * w * 0.14f, cy + h * 0.22f, ink);
            geo.end();
            RenderSystem.enableDepthTest();
        }
    }
}
