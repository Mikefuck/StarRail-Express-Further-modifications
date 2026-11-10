package com.habitrain.core.game.sre.role.idiot;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** No vanilla egg hit/damage/hatching path is ever invoked. */
public final class IdiotEggEntity extends ThrowableItemProjectile {
    public IdiotEggEntity(EntityType<? extends IdiotEggEntity> type, Level level) { super(type, level); }
    @Override protected Item getDefaultItem() { return IdiotContent.EGG; }
    @Override protected boolean canHitEntity(Entity entity) {
        return !(entity instanceof IdiotChickEntity) && super.canHitEntity(entity);
    }
    @Override protected void onHit(HitResult result) {
        if (!level().isClientSide) IdiotService.impact(this, result);
    }
    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && (tickCount >= IdiotRules.EGG_LIFETIME || !IdiotService.valid(this))) discard();
    }
}
