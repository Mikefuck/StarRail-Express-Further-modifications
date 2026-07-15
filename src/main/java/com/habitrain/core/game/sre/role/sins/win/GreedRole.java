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

public final class GreedRole extends CustomWinnerRole {
    public GreedRole(ResourceLocation id, int color, boolean isInnocent, boolean canUseKiller,
                     MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        super(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
    }

    @Override
    public List<ShopEntry> getShopEntries() {
        return SevenSinShops.greedShop();
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return WinStatus.NOT_MODIFY;
        }
        // Instant win is triggered from GreedComponent; checkWin reports CUSTOM when complete.
        if (SinVictoryHooks.isGreedCollectionComplete(level, player)) {
            return WinStatus.CUSTOM;
        }
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        if (original) {
            return true;
        }
        if (winStatus != WinStatus.CUSTOM || player == null) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        return SevenSins.GREED != null
                && SevenSins.GREED_ID.equals(getIdentifier())
                && SinVictoryHooks.isGreedCollectionComplete(level, player);
    }
}
