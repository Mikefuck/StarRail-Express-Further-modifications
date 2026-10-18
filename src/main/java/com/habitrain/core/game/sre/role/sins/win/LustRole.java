package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
import com.habitrain.core.game.sre.role.sins.component.LustPolicy;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.game.GameUtils.WinStatus;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.agmas.harpymodloader.component.WorldModifierComponent;

public final class LustRole extends CustomWinnerRole {
    public LustRole(ResourceLocation id, int color, boolean isInnocent, boolean canUseKiller,
                    MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        super(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
    }

    @Override
    public List<ShopEntry> getShopEntries() {
        return SevenSinShops.lustShop();
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        if (player == null || !(player.level() instanceof ServerLevel level) || HabiModifiers.CHARMED == null) return false;
        // LustFollowVictoryMixin settles other players first, including their custom winner hooks.
        var result = SREGameRoundEndComponent.KEY.get(level);
        Set<UUID> survivors = result.players.stream()
                .filter(data -> !data.wasDead())
                .map(data -> data.player().getId()).collect(Collectors.toSet());
        Set<UUID> winners = result.players.stream()
                .filter(SREGameRoundEndComponent.RoundEndData::hasWin)
                .map(data -> data.player().getId()).collect(Collectors.toSet());
        Set<UUID> marked = Set.copyOf(WorldModifierComponent.getInstance(level)
                .getAllWithModifier(HabiModifiers.CHARMED));
        return LustPolicy.canFollow(survivors.contains(player.getUUID()), marked, winners, survivors);
    }
}
