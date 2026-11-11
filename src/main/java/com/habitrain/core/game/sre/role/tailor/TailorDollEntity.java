package com.habitrain.core.game.sre.role.tailor;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.util.Optional;
import java.util.UUID;

/** A stationary, attackable cloth prop, never a player and never a round participant. */
public final class TailorDollEntity extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(TailorDollEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Optional<UUID>> TARGET = SynchedEntityData.defineId(TailorDollEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(TailorDollEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> STITCHES = SynchedEntityData.defineId(TailorDollEntity.class, EntityDataSerializers.INT);

    public TailorDollEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setNoAi(true); setNoGravity(true); setSilent(true);
    }
    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.MOVEMENT_SPEED, 0);
    }
    @Override protected void registerGoals() {}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty()); builder.define(TARGET, Optional.empty());
        builder.define(NAME, "Doll"); builder.define(STITCHES, 0);
    }
    public void setup(UUID owner, UUID target, String name, float yaw) {
        entityData.set(OWNER, Optional.of(owner)); entityData.set(TARGET, Optional.of(target));
        entityData.set(NAME, name); setYRot(yaw); setYBodyRot(yaw); setYHeadRot(yaw);
    }
    public UUID ownerId() { return entityData.get(OWNER).orElse(null); }
    public UUID targetId() { return entityData.get(TARGET).orElse(null); }
    public String targetName() { return entityData.get(NAME); }
    public int stitches() { return entityData.get(STITCHES); }
    public boolean complete() { return stitches() >= DollTailorRules.SEWING_TICKS; }
    public void stitches(int ticks) { entityData.set(STITCHES, ticks); }
    @Override public boolean isPushable() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean requiresCustomPersistence() { return true; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPickable() { return complete() && !isRemoved(); }
    @Override public void push(double x, double y, double z) {}
    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && complete() && amount > 0 && source.getEntity() != null) {
            DollTailorService.destroy(this, true);
            return true;
        }
        return false;
    }
    @Override public void tick() {
        super.tick();
        setDeltaMovement(0, 0, 0);
        if (level() instanceof ServerLevel && !DollTailorService.owns(this)) {
            // Round props must never survive a restart, stale chunk, or owner cleanup.
            discard();
        }
    }
    public void burst() {
        if (!(level() instanceof ServerLevel level)) return;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.WHITE_WOOL.defaultBlockState()),
                getX(), getY() + 1, getZ(), 65, .35, .7, .35, .12);
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + .8, getZ(), 25, .4, .5, .4, .06);
        level.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY() + 1, getZ(), 30, .3, .6, .3, .08);
        level.playSound(null, blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, .8F, .65F);
        level.playSound(null, blockPosition(), SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 1F, .7F);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); }
    @Override public void readAdditionalSaveData(CompoundTag tag) { super.readAdditionalSaveData(tag); }
}
