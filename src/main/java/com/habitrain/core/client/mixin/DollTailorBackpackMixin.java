package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.InventoryShopLayoutAccess;
import com.habitrain.core.client.tailor.*;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.network.DollTailorActionPayload;
import com.habitrain.core.network.DollTailorStatePayload;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import java.util.UUID;

@Mixin(value = LimitedInventoryScreen.class, remap = false)
public abstract class DollTailorBackpackMixin extends Screen implements DollTailorRosterView {
    @Unique private PlayerPaginationHelper<DollTailorStatePayload.Entry> habitrain$patterns;
    @Unique private List<UUID> habitrain$rosterIds = List.of();
    protected DollTailorBackpackMixin(Component title) { super(title); }
    @Unique private boolean habitrain$isTailor() {
        return HabiRoles.isHabiRole(Minecraft.getInstance().player, HabiRoles.DOLL_TAILOR_ID);
    }
    @Unique private PlayerPaginationHelper<DollTailorStatePayload.Entry> habitrain$patterns() {
        if (habitrain$patterns == null) habitrain$patterns = new PlayerPaginationHelper<>(
                (x, y, entry, index) -> addRenderableWidget(new DollTailorPlayerWidget(x, y, entry)),
                new PlayerPaginationHelper.PaginationTextProvider() {
                    public String getPageTranslationKey() { return "hud.pagination.page"; }
                    public String getPrevTranslationKey() { return "hud.pagination.prev"; }
                    public String getNextTranslationKey() { return "hud.pagination.next"; }
                });
        return habitrain$patterns;
    }
    @Override public void habitrain$tailorRoster(List<DollTailorStatePayload.Entry> entries) {
        if (!habitrain$isTailor()) return;
        var ids = entries.stream().map(DollTailorStatePayload.Entry::id).toList();
        if (ids.equals(habitrain$rosterIds)) return;
        habitrain$rosterIds = ids;
        habitrain$patterns().setPlayerEntries(entries);
        habitrain$patterns().refreshPage(this);
    }
    @Inject(method = "init", at = @At("TAIL"), remap = true)
    private void habitrain$openPatterns(CallbackInfo ci) {
        if (!habitrain$isTailor()) return;
        InventoryShopLayoutAccess.reserveRoleSelection(this);
        habitrain$patterns().clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
        habitrain$rosterIds = List.of();
        habitrain$patterns().setPlayerEntries(DollTailorClient.state().entries());
        habitrain$patterns().refreshPage(this);
        habitrain$rosterIds = DollTailorClient.state().entries().stream().map(DollTailorStatePayload.Entry::id).toList();
        if (ClientPlayNetworking.canSend(DollTailorActionPayload.TYPE)) ClientPlayNetworking.send(new DollTailorActionPayload(null));
    }
    @Inject(method = "render", at = @At("HEAD"), remap = true)
    private void habitrain$drawPatterns(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!habitrain$isTailor()) {
            if (habitrain$patterns != null) habitrain$patterns.clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
            return;
        }
        int centerY = InventoryShopLayoutAccess.centerY(this);
        habitrain$patterns().drawPagination(g, this, centerY);
        if (habitrain$rosterIds.isEmpty()) g.drawCenteredString(Minecraft.getInstance().font,
                Component.translatable("screen.habitrain_core.doll_tailor.empty"), width / 2, centerY + 80, 0xDABF89);
    }
}
