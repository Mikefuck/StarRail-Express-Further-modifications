package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.game.GameUtils.WinStatus;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class PrideRole extends CustomWinnerRole {
    public PrideRole(ResourceLocation id, int color, boolean isInnocent, boolean canUseKiller,
                     MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        super(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
    }

    @Override
    public List<ShopEntry> getShopEntries() {
        return PrideComponent.getActiveShopEntries();
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return WinStatus.NOT_MODIFY;
        }
        if (SinVictoryHooks.isOnlyPrideAlive(level)) {
            SinVictoryHooks.triggerCustomSinWin(level, SevenSins.PRIDE, player);
            return WinStatus.CUSTOM;
        }
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        if (winStatus == WinStatus.CUSTOM) {
            return true;
        }
        return original;
    }
}
