package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.LustCharmPlayerWidget;
import com.habitrain.core.client.gui.InventoryShopLayoutAccess;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.PlayerPaginationHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Mixin(value = LimitedInventoryScreen.class, remap = false)
public abstract class LustBackpackMixin extends Screen {
    @Unique private PlayerPaginationHelper<LustComponent.CharmEntry> habitrain$charmPages;
    @Unique private List<LustComponent.CharmEntry> habitrain$lastCharms;
    @Unique private UUID habitrain$lastSelected;
    @Unique private boolean habitrain$lastReady;

    protected LustBackpackMixin(Component title) { super(title); }

    @Unique private boolean habitrain$isLust() {
        var self = Minecraft.getInstance().player;
        return self != null && !self.isSpectator() && HabiRoles.isHabiRole(self, SevenSins.LUST);
    }

    @Unique private PlayerPaginationHelper<LustComponent.CharmEntry> habitrain$charmPages() {
        if (habitrain$charmPages == null) {
            habitrain$charmPages = new PlayerPaginationHelper<>(
                    (x, y, entry, index) -> addRenderableWidget(new LustCharmPlayerWidget(x, y, entry)),
                    new PlayerPaginationHelper.PaginationTextProvider() {
                        public String getPageTranslationKey() { return "hud.pagination.page"; }
                        public String getPrevTranslationKey() { return "hud.pagination.prev"; }
                        public String getNextTranslationKey() { return "hud.pagination.next"; }
                    });
        }
        return habitrain$charmPages;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$initCharms(CallbackInfo ci) {
        if (habitrain$isLust()) InventoryShopLayoutAccess.reserveRoleSelection(this);
        habitrain$lastCharms = null;
        if (habitrain$charmPages != null) habitrain$charmPages.clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$renderCharms(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!habitrain$isLust()) {
            if (habitrain$charmPages != null) habitrain$charmPages.clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
            return;
        }
        var state = LustComponent.KEY.get(Minecraft.getInstance().player);
        boolean ready = state.selectionTicksLeft() == 0;
        if (!state.roster().equals(habitrain$lastCharms)
                || !Objects.equals(state.selected(), habitrain$lastSelected) || ready != habitrain$lastReady) {
            habitrain$lastCharms = state.roster();
            habitrain$lastSelected = state.selected();
            habitrain$lastReady = ready;
            habitrain$charmPages().setPlayerEntries(state.roster());
            habitrain$charmPages().refreshPage(this);
        }
        int centerY = InventoryShopLayoutAccess.centerY(this);
        habitrain$charmPages().drawPagination(graphics, this, centerY);
        Component hint = state.roster().isEmpty()
                ? Component.translatable("message.habitrain_core.sin_lust.no_targets")
                : ready ? Component.translatable("screen.habitrain_core.sin_lust.choose_hint")
                : Component.translatable("message.habitrain_core.sin_lust.selection_cooldown", (state.selectionTicksLeft() + 19) / 20);
        graphics.drawCenteredString(Minecraft.getInstance().font, hint, width / 2, centerY + 58, 0xFFEF9BCC);
    }
}
