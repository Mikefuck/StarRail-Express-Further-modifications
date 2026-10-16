package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preserve upstream winner decisions and stats; followers read the completed decisions last. */
@Mixin(value = GameUtils.class, remap = false)
public abstract class LustFollowVictoryMixin {
    @Redirect(method = "recordWinStats", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;", remap = true))
    private static List<ServerPlayer> habitrain$settleLustLast(ServerLevel level) {
        List<ServerPlayer> ordered = new ArrayList<>(level.players());
        ordered.sort(Comparator.comparing(p -> HabiRoles.isHabiRole(p, SevenSins.LUST)));
        return ordered;
    }
}
