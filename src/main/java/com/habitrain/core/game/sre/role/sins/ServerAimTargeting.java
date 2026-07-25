package com.habitrain.core.game.sre.role.sins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative player ray targeting. Client UUIDs are hints, never authority. */
public final class ServerAimTargeting {
    public static final double DEFAULT_RANGE = 12.0D;

    private ServerAimTargeting() {}

    public static @Nullable ServerPlayer resolve(
            ServerPlayer self, @Nullable UUID hintedTarget, double range) {
        if (self == null || !(self.level() instanceof ServerLevel level)) return null;

        Vec3 eye = self.getEyePosition();
        Vec3 end = eye.add(self.getViewVector(1.0F).scale(range));
        return level.players().stream()
                .filter(candidate -> candidate != self)
                .filter(ServerAimTargeting::isAliveParticipant)
                .filter(candidate -> candidate.distanceToSqr(self) <= range * range)
                .filter(self::hasLineOfSight)
                .map(candidate -> new Hit(candidate,
                        candidate.getBoundingBox().inflate(0.35D).clip(eye, end)))
                .filter(hit -> hit.position().isPresent())
                .min(Comparator
                        .comparingDouble((Hit hit) -> eye.distanceToSqr(hit.position().orElse(end)))
                        .thenComparing(hit -> hintedTarget != null
                                && hintedTarget.equals(hit.player().getUUID()) ? 0 : 1))
                .map(Hit::player)
                .orElse(null);
    }

    public static @Nullable ServerPlayer resolve(ServerPlayer self, @Nullable UUID hintedTarget) {
        return resolve(self, hintedTarget, DEFAULT_RANGE);
    }

    private static boolean isAliveParticipant(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator();
    }

    private record Hit(ServerPlayer player, Optional<Vec3> position) {}
}
