package com.habitrain.core.game.sre.role.tailor;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared gameplay contract; times are server ticks, independent of frame rate. */
public final class DollTailorRules {
    public static final int OBSERVATION_TICKS = 15 * 20;
    public static final double OBSERVATION_RANGE = 7.0;
    public static final int SEWING_TICKS = 10 * 20;
    public static final int PRICE = 200;

    private DollTailorRules() {}

    /** Shared by server progress and client particles: the view ray must reach the target's body. */
    public static boolean canObserve(Player observer, Player target) {
        if (observer == target || observer.level() != target.level()
                || observer.distanceToSqr(target) > OBSERVATION_RANGE * OBSERVATION_RANGE) return false;
        Vec3 eye = observer.getEyePosition();
        var hit = target.getBoundingBox().clip(eye, eye.add(observer.getLookAngle().scale(OBSERVATION_RANGE)));
        return hit.isPresent() && observer.level().clip(new ClipContext(eye, hit.get(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer)).getType() == HitResult.Type.MISS;
    }

    public static int observe(int previous, boolean inRange, boolean visible) {
        int bounded = Math.clamp(previous, 0, OBSERVATION_TICKS);
        return inRange && visible ? Math.min(OBSERVATION_TICKS, bounded + 1) : bounded;
    }

    public static boolean canSew(boolean alive, boolean patternReady, int balance,
                                 boolean hasDoll, boolean sewing) {
        return alive && patternReady && balance >= PRICE && !hasDoll && !sewing;
    }
}
