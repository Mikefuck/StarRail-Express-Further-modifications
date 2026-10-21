package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.TicketInventoryLayer;
import com.habitrain.core.client.gui.TicketMenuTray;
import com.habitrain.core.client.gui.TicketPerformanceButton;
import com.habitrain.core.client.gui.TicketShopLayout;
import com.habitrain.core.client.gui.TicketShopRenderer;
import com.habitrain.core.client.gui.TicketShopStyleButton;
import com.habitrain.core.client.gui.InventoryShopLayoutAccess;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedHandledScreen;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.agmas.noellesroles.client.widget.ExecutionerPlayerWidget;
import org.lwjgl.glfw.GLFW;

/**
 * 背包车票化：等待开局时把上游背包面板换成「乘车凭证」车票页（{@link TicketInventoryLayer}）；
 * 对局中将商店、快捷栏和角色选人区统一排布；放不下时恢复上游商店布局。
 *
 * <p>上游的菜单按钮、翻页箭头与参与按钮仍然创建（其它 mixin 依赖它们），只是隐藏起来，
 * 由邮票与票价框代为触发；等待时快捷栏槽位改排到车票的「随身行李」格里。</p>
 */
@Mixin(LimitedInventoryScreen.class)
public abstract class LimitedInventoryTicketMixin extends LimitedHandledScreen<InventoryMenu>
        implements TicketShopRenderer.ScreenAccess, InventoryShopLayoutAccess {
    @Unique
    private static final int[] HABITRAIN$HIDDEN_SLOT = {-10000, -10000};

    @Shadow(remap = false)
    @Final
    private ArrayList<LimitedInventoryScreen.WaitingMenuCellButton> waitingMenuButtons;
    @Shadow(remap = false)
    private Button waitingPrevPageButton;
    @Shadow(remap = false)
    private Button waitingNextPageButton;
    @Shadow(remap = false)
    private Button participationButton;
    @Shadow(remap = false)
    public ArrayList<Button> menuSelections;
    @Shadow(remap = false)
    public Button menuButton;
    @Shadow(remap = false)
    public boolean isMenuOpen;
    @Shadow(remap = false)
    protected abstract void updateWaitingMenuVisibility();
    @Shadow(remap = false)
    @Final
    private ArrayList<LimitedInventoryScreen.StoreItemWidget> shopWidgets;
    @Shadow(remap = false)
    private Button shopPrevPageButton;
    @Shadow(remap = false)
    private Button shopNextPageButton;
    @Shadow(remap = false)
    private int shopCurrentPage;
    @Shadow(remap = false)
    private int shopTotalPages;
    @Shadow(remap = false)
    private int shopNavY;
    @Shadow(remap = false)
    protected abstract void refreshShopLayout();

    @Unique
    private TicketInventoryLayer habitrain$ticket;
    @Unique
    private TicketMenuTray habitrain$tray;
    @Unique
    private TicketPerformanceButton habitrain$modeButton;
    @Unique
    private TicketShopRenderer habitrain$shop;
    @Unique
    private boolean habitrain$shopActive;
    @Unique
    private boolean habitrain$expandedShop;
    @Unique
    private boolean habitrain$roleSelection;
    @Unique
    private boolean habitrain$initializing;
    @Unique
    private final Map<Button, Integer> habitrain$directRoleWidgets = new IdentityHashMap<>();
    @Unique
    private boolean habitrain$directRoleOverflow;

    protected LimitedInventoryTicketMixin(InventoryMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Unique
    private static boolean habitrain$gameRunning() {
        return SREClient.gameComponent != null && SREClient.gameComponent.isRunning();
    }

    @Unique
    private boolean habitrain$ticketView() {
        return habitrain$ticket != null && habitrain$ticket.usable() && !habitrain$gameRunning();
    }

    @Unique
    private boolean habitrain$gameView() {
        return habitrain$tray != null && habitrain$ticket != null && habitrain$ticket.usable() && habitrain$gameRunning();
    }

    @Override
    public int habitrain$inventoryCenterY() {
        return habitrain$gameView() && habitrain$expandedShop
                ? this.y : (this.height - TicketShopLayout.HOTBAR_HEIGHT) / 2;
    }

    @Override
    public void habitrain$reserveRoleSelection() {
        if (habitrain$roleSelection) return;
        habitrain$roleSelection = true;
        if (!habitrain$initializing && habitrain$shop != null) refreshShopLayout();
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$resetShopLayout(CallbackInfo ci) {
        habitrain$initializing = true;
        // Cleared per open; {@link #habitrain$roleSelectionVisible()} re-derives it from the widgets
        // that role screens actually added during this init, independent of injection order.
        habitrain$roleSelection = false;
        habitrain$expandedShop = false;
        habitrain$directRoleWidgets.clear();
        habitrain$directRoleOverflow = false;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$installTicket(CallbackInfo ci) {
        if (habitrain$modeButton == null) {
            habitrain$modeButton = new TicketPerformanceButton();
        }
        if (habitrain$ticket == null) {
            habitrain$ticket = new TicketInventoryLayer(habitrain$modeButton);
        }
        habitrain$ticket.rebuild(waitingMenuButtons, participationButton);
        habitrain$ticket.layout(this.width, this.height);
        this.addWidget(habitrain$ticket);
        if (habitrain$tray == null) {
            habitrain$tray = new TicketMenuTray(habitrain$modeButton,
                    new TicketShopStyleButton(this::habitrain$syncTicketState));
        }
        LimitedInventoryScreen self = (LimitedInventoryScreen) (Object) this;
        habitrain$tray.rebuild(menuSelections, self::toggleViewMenu, () -> this.isMenuOpen);
        habitrain$tray.layout(this.width, this.height);
        this.addWidget(habitrain$tray);
        if (habitrain$shop == null) habitrain$shop = new TicketShopRenderer();
        habitrain$initializing = false;
        habitrain$syncTicketState();
        refreshShopLayout();
        habitrain$hideUpstreamWaitingWidgets();
        habitrain$hideUpstreamGameMenu();
    }

    @Inject(method = "updateWaitingMenuVisibility", at = @At("TAIL"), remap = false)
    private void habitrain$keepUpstreamWaitingHidden(CallbackInfo ci) {
        habitrain$hideUpstreamWaitingWidgets();
        habitrain$hideUpstreamGameMenu();
    }

    @Inject(method = "toggleViewMenu", at = @At("TAIL"), remap = false)
    private void habitrain$keepUpstreamGameMenuHidden(boolean open, CallbackInfo ci) {
        habitrain$hideUpstreamGameMenu();
    }

    @Unique
    private void habitrain$syncTicketState() {
        if (habitrain$ticket == null || habitrain$tray == null) return;
        habitrain$ticket.sync(waitingMenuButtons, participationButton);
        habitrain$tray.sync(menuSelections);
        boolean lightweight = ClientVisualPreferences.isLightweightInventory();
        habitrain$ticket.setLightweight(lightweight);
        habitrain$tray.setLightweight(lightweight);
        habitrain$ticket.setShowing(habitrain$ticketView());
        habitrain$tray.setActive(habitrain$gameView());
        boolean decoratedShop = habitrain$gameView() && ClientVisualPreferences.isDecoratedShopEnabled();
        if (habitrain$shop != null && decoratedShop != habitrain$shopActive) {
            habitrain$shopActive = decoratedShop;
            refreshShopLayout();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$prepareFrame(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        habitrain$syncTicketState();
        habitrain$syncDirectRoleWidgets();
        habitrain$hideUpstreamWaitingWidgets();
        habitrain$hideUpstreamGameMenu();
        if (habitrain$gameView()) {
            LimitedInventoryScreen self = (LimitedInventoryScreen) (Object) this;
            habitrain$shop.beginFrame(self.getShopEntries(), habitrain$tray.isMouseOver(mouseX, mouseY));
        }
    }

    /** True while the Executioner heads this role adds directly are still on the screen. */
    @Unique
    private boolean habitrain$hasDirectRoleWidget() {
        for (var child : this.children()) {
            if (child instanceof ExecutionerPlayerWidget) return true;
        }
        return false;
    }

    /**
     * The role-selector row exists on the screen itself, no matter which role mixin added it or in
     * which {@code init} order. The shop must leave that row below the hotbar, otherwise its extra
     * product rows push the hotbar down into the player heads.
     */
    @Unique
    private boolean habitrain$roleSelectionVisible() {
        return habitrain$roleSelection
                || !habitrain$directRoleWidgets.isEmpty()
                || habitrain$hasDirectRoleWidget()
                || InventoryShopLayoutAccess.hasRoleSelection(this);
    }

    /** Executioner creates its heads directly instead of using PlayerPaginationHelper. */
    @Unique
    private void habitrain$syncDirectRoleWidgets() {
        habitrain$directRoleWidgets.keySet().removeIf(widget -> !this.children().contains(widget));
        boolean overflow = false;
        for (var child : this.children()) {
            if (child instanceof ExecutionerPlayerWidget widget) {
                habitrain$directRoleWidgets.putIfAbsent(widget, widget.getY());
                overflow |= widget.getX() - 7 < 8 || widget.getX() + 23 > this.width - 8;
            }
        }
        boolean changed = overflow != habitrain$directRoleOverflow;
        habitrain$directRoleOverflow = overflow;
        // Heads may appear after the shop was laid out (roster updates, late payloads).
        if (habitrain$roleSelectionVisible()) habitrain$reserveRoleSelection();
        if (changed && habitrain$shop != null) refreshShopLayout();
        int offset = InventoryShopLayoutAccess.offset(this);
        habitrain$directRoleWidgets.forEach((widget, originalY) -> widget.setY(originalY + offset));
    }

    @Inject(method = "refreshShopLayout", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$layoutShop(CallbackInfo ci) {
        // Restore the upstream origin before every fit, including resize and artwork fallback.
        this.y = (this.height - this.backgroundHeight) / 2;
        habitrain$expandedShop = false;
        if (habitrain$shop != null) habitrain$shop.clearLayout();
        if (!habitrain$gameView() || !ClientVisualPreferences.isDecoratedShopEnabled()
                || habitrain$shop == null || habitrain$directRoleOverflow) return;
        boolean roleSelection = habitrain$roleSelectionVisible();
        if (roleSelection) habitrain$roleSelection = true;
        var fitted = TicketShopLayout.fit(this.width, this.height, shopWidgets.size(), roleSelection);
        if (fitted.isEmpty()) return;
        TicketShopLayout layout = fitted.get();
        this.y = layout.hotbarY();
        habitrain$expandedShop = true;
        habitrain$shop.layout(layout, shopWidgets, shopPrevPageButton, shopNextPageButton);
        shopCurrentPage = layout.page();
        shopTotalPages = layout.pages();
        shopNavY = layout.navY();
        ci.cancel();
    }

    @Inject(method = "refreshShopLayout", at = @At("TAIL"), remap = false)
    private void habitrain$restoreShopBounds(CallbackInfo ci) {
        if (habitrain$expandedShop) return;
        for (Button widget : shopWidgets) {
            widget.setWidth(16);
            widget.setHeight(16);
        }
        for (Button button : new Button[] {shopPrevPageButton, shopNextPageButton}) {
            if (button != null) {
                button.setWidth(20);
                button.setHeight(20);
            }
        }
    }

    @Override
    public boolean habitrain$renderShopItem(LimitedInventoryScreen.StoreItemWidget widget, GuiGraphics graphics,
                                           int mouseX, int mouseY) {
        return habitrain$gameView() && habitrain$expandedShop && habitrain$renderSafely(graphics,
                () -> habitrain$shop.renderItem(widget, graphics, mouseX, mouseY));
    }

    /** The ticket footer already contains the page label. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
    private int habitrain$shopPageLabel(GuiGraphics graphics, Font font, Component text, int x, int y, int color,
                                        boolean shadow, Operation<Integer> original) {
        return habitrain$expandedShop ? 0 : original.call(graphics, font, text, x, y, color, shadow);
    }

    /** 对局中：上游右下角的「菜单」按钮与展开的竖列由邮票册代替。 */
    @Unique
    private void habitrain$hideUpstreamGameMenu() {
        if (!habitrain$gameView()) {
            return;
        }
        if (menuButton != null) {
            menuButton.visible = false;
            menuButton.active = false;
        }
        if (menuSelections != null) {
            for (Button button : menuSelections) {
                button.visible = false;
                button.active = false;
            }
        }
    }

    @Unique
    private void habitrain$hideUpstreamWaitingWidgets() {
        if (!habitrain$ticketView()) {
            return;
        }
        for (Button button : waitingMenuButtons) {
            button.visible = false;
            button.active = false;
        }
        habitrain$hideButton(waitingPrevPageButton);
        habitrain$hideButton(waitingNextPageButton);
        habitrain$hideButton(participationButton);
    }

    @Unique
    private static void habitrain$hideButton(Button button) {
        if (button == null) return;
        button.visible = false;
        button.active = false;
    }

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$drawTicket(GuiGraphics graphics, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (habitrain$ticket == null || habitrain$tray == null) {
            return;
        }
        boolean show = habitrain$ticketView();
        boolean game = habitrain$gameView();
        habitrain$ticket.setShowing(show);
        habitrain$tray.setActive(game);
        if (!show && !game) {
            return;
        }
        if (habitrain$renderSafely(graphics, () -> {
            if (show) {
                habitrain$ticket.render(graphics, mouseX, mouseY);
            } else {
                habitrain$hideUpstreamGameMenu();
                habitrain$shop.renderPanel(graphics);
                habitrain$tray.renderStrip(graphics, this.x, this.y, mouseX, mouseY);
            }
        })) {
            ci.cancel();
        }
    }

    @Unique
    private boolean habitrain$renderSafely(GuiGraphics graphics, Runnable draw) {
        var pose = graphics.pose().last();
        try {
            draw.run();
            return true;
        } catch (RuntimeException e) {
            // Restore the original coordinate system before falling back in this very frame.
            while (graphics.pose().last() != pose) graphics.pose().popPose();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            habitrain$ticket.fail(e);
            habitrain$ticket.setShowing(false);
            habitrain$tray.setActive(false);
            updateWaitingMenuVisibility();
            refreshShopLayout();
            return false;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void habitrain$drawTicketOverlay(GuiGraphics graphics, int mouseX, int mouseY, float delta,
                                             CallbackInfo ci) {
        if (habitrain$ticketView()) {
            habitrain$renderSafely(graphics, () -> habitrain$ticket.renderOverlay(graphics, mouseX, mouseY));
        } else if (habitrain$gameView()) {
            // The album is in front of role/shop widgets. Its input has the same priority below.
            habitrain$renderSafely(graphics, () -> {
                habitrain$shop.renderTooltip(graphics, mouseX, mouseY);
                habitrain$tray.renderTray(graphics, mouseX, mouseY);
                habitrain$tray.renderOverlay(graphics, mouseX, mouseY);
            });
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void habitrain$tickTicket(CallbackInfo ci) {
        habitrain$syncTicketState();
        if (habitrain$ticketView()) {
            habitrain$ticket.tick();
        } else if (habitrain$gameView()) {
            habitrain$tray.tick();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        habitrain$syncTicketState();
        if (habitrain$ticketView() && habitrain$ticket.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(habitrain$ticket);
            return true;
        }
        if (habitrain$gameView() && habitrain$tray.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(habitrain$tray);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean isClickOutsideBounds(double mouseX, double mouseY, int left, int top, int button) {
        // Touchscreen mode must use the relocated ticket's bounds, including its real hotbar slots.
        if (habitrain$ticketView()) return !habitrain$ticket.isMouseOver(mouseX, mouseY);
        if (habitrain$expandedShop && habitrain$shop.contains(mouseX, mouseY)) return false;
        if (habitrain$gameView() && habitrain$tray.isMouseOver(mouseX, mouseY)) return false;
        return super.isClickOutsideBounds(mouseX, mouseY, left, top, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (habitrain$ticketView() && habitrain$ticket.isMouseOver(mouseX, mouseY)
                && habitrain$ticket.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (habitrain$gameView() && habitrain$tray.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_F6 && (habitrain$ticketView() || habitrain$gameView())) {
            habitrain$modeButton.toggleMode();
            habitrain$syncTicketState();
            return true;
        }
        if (habitrain$ticketView() && habitrain$ticket.keyPressed(key, scanCode, modifiers)) {
            setFocused(habitrain$ticket);
            return true;
        }
        if (habitrain$gameView() && habitrain$tray.keyPressed(key, scanCode, modifiers)) {
            setFocused(habitrain$tray);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void removed() {
        try {
            super.removed();
        } finally {
            if (habitrain$ticket != null) {
                habitrain$ticket.setShowing(false);
                habitrain$ticket.releaseResources();
            }
            if (habitrain$tray != null) {
                habitrain$tray.setActive(false);
                habitrain$tray.releaseResources();
            }
            if (habitrain$shop != null) habitrain$shop.beginFrame(List.of(), true);
        }
    }

    // ==================== 快捷栏槽位 ====================

    @Unique
    private int[] habitrain$slotPosition(Slot slot) {
        if (slot == null || slot.index < 36 || slot.index > 44 || !habitrain$ticketView()) {
            return null;
        }
        int[] position = habitrain$ticket.slotPosition(slot.index - 36);
        return position != null ? position : HABITRAIN$HIDDEN_SLOT;
    }

    @Inject(method = "getSlotRenderX", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$ticketSlotX(Slot slot, CallbackInfoReturnable<Integer> cir) {
        int[] position = habitrain$slotPosition(slot);
        if (position != null) {
            cir.setReturnValue(position[0] - this.x);
        }
    }

    @Inject(method = "getSlotRenderY", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$ticketSlotY(Slot slot, CallbackInfoReturnable<Integer> cir) {
        int[] position = habitrain$slotPosition(slot);
        if (position != null) {
            cir.setReturnValue(position[1] - (this.y - getYOffset()));
        }
    }

    @Inject(method = "getSlotRenderScale", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$ticketSlotScale(Slot slot, CallbackInfoReturnable<Float> cir) {
        if (habitrain$slotPosition(slot) != null) {
            cir.setReturnValue(habitrain$ticket.slotScale());
        }
    }

    /** 上游把物品说明固定画在屏幕正中，会压住车票；车票页里改为跟随鼠标。 */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/client/gui/screen/ingame/LimitedInventoryScreen;drawMouseoverTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V"))
    private void habitrain$ticketTooltip(LimitedInventoryScreen self, GuiGraphics graphics, int mouseX, int mouseY,
                                         Operation<Void> original) {
        if (!habitrain$ticketView() && !habitrain$expandedShop) {
            if (habitrain$gameView() && habitrain$tray.isMouseOver(mouseX, mouseY)) return;
            original.call(self, graphics, mouseX, mouseY);
            return;
        }
        if (!this.handler.getCarried().isEmpty() || this.focusedSlot == null || !this.focusedSlot.hasItem()) {
            return;
        }
        ItemStack stack = this.focusedSlot.getItem();
        List<Component> lines = new ArrayList<>(this.getTooltipFromItem(stack));
        if (this.minecraft != null && this.minecraft.player != null) {
            float cooldown = this.minecraft.player.getCooldowns().getCooldownPercent(stack.getItem(), 0.0f);
            if (cooldown > 0.0f) {
                lines.add(Component.translatable("inventory.habitrain_core.ticket.cooldown",
                        Math.round(cooldown * 100.0f)).withStyle(ChatFormatting.GRAY));
            }
        }
        graphics.renderTooltip(this.font, lines, stack.getTooltipImage(), mouseX, mouseY);
    }
}
