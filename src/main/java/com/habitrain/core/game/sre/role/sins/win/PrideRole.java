package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
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
        return SevenSinShops.prideShop();
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        // Side-effect free: framework calls win() → customWinnerWin when CUSTOM.
        // AllowGameEnd path also triggers custom win via SinVictoryHooks.
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return WinStatus.NOT_MODIFY;
        }
        if (SinVictoryHooks.isOnlyPrideAlive(level)) {
            return WinStatus.CUSTOM;
        }
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        // Only claim CUSTOM when this pride is the sole-alive custom winner — never any CUSTOM.
        if (original) {
            return true;
        }
        if (winStatus != WinStatus.CUSTOM || player == null) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        return SevenSins.PRIDE != null
                && SevenSins.PRIDE_ID.equals(getIdentifier())
                && SinVictoryHooks.isOnlyPrideAlive(level);
    }
}
