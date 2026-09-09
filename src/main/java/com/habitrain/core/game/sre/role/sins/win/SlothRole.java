package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.game.GameUtils.WinStatus;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class SlothRole extends CustomWinnerRole {
    public SlothRole(ResourceLocation id, int color, boolean isInnocent, boolean canUseKiller,
                     MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        super(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
    }

    @Override
    public List<ShopEntry> getShopEntries() {
        return SevenSinShops.empty();
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        // Rewritten Sloth never steals a faction/timer settlement.
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        if (winStatus != WinStatus.CUSTOM || player == null) {
            return false;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
        return roundEnd.CustomWinnerPlayers != null
                && roundEnd.CustomWinnerPlayers.contains(player.getUUID());
    }
}
