package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.UUID;

/** Server-authoritative player ray targeting. Client UUIDs are hints, never authority. */
public final class ServerAimTargeting {
    public static final double DEFAULT_RANGE = 12.0D;
    private static final double MIN_AIM_COSINE = 0.85D;

    private ServerAimTargeting() {}

    public static @Nullable ServerPlayer resolve(
            ServerPlayer self, @Nullable UUID hintedTarget, double range) {
        if (self == null || !(self.level() instanceof ServerLevel level)) return null;

        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(range));
        double maxDistSqr = range * range;
        return level.players().stream()
                .filter(candidate -> candidate != self)
                .filter(ServerAimTargeting::isAliveParticipant)
                .filter(candidate -> candidate.distanceToSqr(self) <= maxDistSqr)
                .filter(self::hasLineOfSight)
                .map(candidate -> toHit(candidate, eye, look, end))
                .filter(hit -> hit != null)
                .min(Comparator
                        .comparingInt((Hit hit) -> hintedTarget != null
                                && hintedTarget.equals(hit.player().getUUID()) ? 0 : 1)
                        .thenComparingDouble( hit -> eye.distanceToSqr(hit.position())))
                .map(Hit::player)
                .orElse(null);
    }

    public static @Nullable ServerPlayer resolve(ServerPlayer self, @Nullable UUID hintedTarget) {
        return resolve(self, hintedTarget, DEFAULT_RANGE);
    }

    private static @Nullable Hit toHit(ServerPlayer candidate, Vec3 eye, Vec3 look, Vec3 end) {
        var clip = candidate.getBoundingBox().inflate(0.35D).clip(eye, end);
        if (clip.isPresent()) {
            return new Hit(candidate, clip.get());
        }
        Vec3 targetCenter = candidate.getBoundingBox().getCenter();
        Vec3 toTarget = targetCenter.subtract(eye);
        double lenSqr = toTarget.lengthSqr();
        if (lenSqr > 1.0E-6D && toTarget.scale(1.0D / Math.sqrt(lenSqr)).dot(look) >= MIN_AIM_COSINE) {
            return new Hit(candidate, targetCenter);
        }
        return null;
    }

    public static boolean isAliveParticipant(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        try {
            if (EliminatedRestAreaService.isResting(player)
                    || com.habitrain.core.game.sre.mode.RepairModeManager.isRepairer(player.getUUID())) return false;
            var game = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(player.level());
            return game.getGameStatus() == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.ACTIVE
                    && GameUtils.isPlayerAliveAndSurvival(player);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private record Hit(ServerPlayer player, Vec3 position) {}
}

