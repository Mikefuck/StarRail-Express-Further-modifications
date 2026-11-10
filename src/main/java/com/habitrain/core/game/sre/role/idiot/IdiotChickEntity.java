package com.habitrain.core.game.sre.role.idiot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** A non-persistent party prop: no breeding, egg laying, drops, pushing or weapon interception. */
public final class IdiotChickEntity extends Chicken {
    private UUID owner;
    public IdiotChickEntity(EntityType<? extends Chicken> type, Level level) {
        super(type, level);
        setAge(-24000);
        setSilent(true);
    }
    public void owner(UUID id) { owner = id; }
    public UUID owner() { return owner; }
    @Override protected void registerGoals() {}
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override protected void pushEntities() {}
    @Override public void push(Entity other) {}
    @Override public void push(double x, double y, double z) {}
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public InteractionResult mobInteract(Player player, InteractionHand hand) { return InteractionResult.PASS; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel)) return;
        eggTime = Integer.MAX_VALUE;
        setAge(-24000);
        if (tickCount >= IdiotRules.CHICK_LIFETIME || !IdiotService.valid(this)) {
            IdiotService.poof(this);
            discard();
            return;
        }
        if (tickCount % 20 == 1) {
            Player nearest = level().getNearestPlayer(this, 6);
            Vec3 direction = nearest != null && nearest.isAlive() && !nearest.isSpectator() && !nearest.isInvisible()
                    && random.nextBoolean() && hasLineOfSight(nearest)
                    ? nearest.position().subtract(position())
                    : new Vec3(random.nextDouble() - .5, 0, random.nextDouble() - .5);
            direction = new Vec3(direction.x, 0, direction.z).normalize();
            // Ground navigation respects doors/walls and does not chase hidden players.
            getNavigation().moveTo(getX() + direction.x * 3, getY(), getZ() + direction.z * 3, 1.65);
        }
    }
}
