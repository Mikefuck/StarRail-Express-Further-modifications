package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.SlothSleepRosterView;
import com.habitrain.core.client.gui.SlothSleepPlayerWidget;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.network.SlothSleepRosterPayload;
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

/** Reuses Voodoo's in-inventory pagination with server-authorized sleep targets. */
@Mixin(value = LimitedInventoryScreen.class, remap = false)
public abstract class SlothBackpackSleepMixin extends Screen implements SlothSleepRosterView {
    @Unique private PlayerPaginationHelper<SlothSleepRosterPayload.Entry> habitrain$sleepPagination;
    @Unique private boolean habitrain$sleepRosterEmpty;

    protected SlothBackpackSleepMixin(Component title) { super(title); }

    @Unique
    private boolean habitrain$isSloth() {
        var player = Minecraft.getInstance().player;
        return player != null && HabiRoles.isHabiRole(player, SevenSins.SLOTH);
    }

    @Unique
    private PlayerPaginationHelper<SlothSleepRosterPayload.Entry> habitrain$sleepPagination() {
        if (habitrain$sleepPagination == null) {
            habitrain$sleepPagination = new PlayerPaginationHelper<>(
                    (x, y, entry, index) -> addRenderableWidget(new SlothSleepPlayerWidget(x, y, entry)),
                    new PlayerPaginationHelper.PaginationTextProvider() {
                        public String getPageTranslationKey() { return "hud.pagination.page"; }
                        public String getPrevTranslationKey() { return "hud.pagination.prev"; }
                        public String getNextTranslationKey() { return "hud.pagination.next"; }
                    });
        }
        return habitrain$sleepPagination;
    }

    @Override
    public void habitrain$setSleepTargets(List<SlothSleepRosterPayload.Entry> entries) {
        if (!habitrain$isSloth()) return;
        habitrain$sleepRosterEmpty = entries.isEmpty();
        var pagination = habitrain$sleepPagination();
        pagination.setPlayerEntries(entries);
        pagination.refreshPage(this);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$requestSlothSleepRoster(CallbackInfo ci) {
        if (!habitrain$isSloth()) return;
        habitrain$sleepPagination().clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
        habitrain$sleepRosterEmpty = false;
        PayloadSenders.requestSlothSleepRoster();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$renderSleepPagination(GuiGraphics graphics, int mouseX, int mouseY,
                                                float delta, CallbackInfo ci) {
        if (!habitrain$isSloth()) {
            if (habitrain$sleepPagination != null) {
                habitrain$sleepPagination.clearManagedWidgets((PlayerPaginationHelper.ScreenWithChildren) this);
            }
            return;
        }
        int centerY = (height - 32) / 2;
        habitrain$sleepPagination().drawPagination(graphics, this, centerY);
        if (habitrain$sleepRosterEmpty) {
            graphics.drawCenteredString(Minecraft.getInstance().font,
                    Component.translatable("screen.habitrain_core.sin_sloth.no_targets"),
                    width / 2, centerY + 80, 0xFFB8AECF);
        }
    }
}