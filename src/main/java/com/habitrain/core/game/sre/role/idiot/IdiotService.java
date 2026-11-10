package com.habitrain.core.game.sre.role.idiot;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchPhase;
import com.habitrain.core.api.match.MatchStateApi;
import com.habitrain.core.api.role.v2.behavior.*;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.network.IdiotEffectPayload;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** All gameplay rolls, caps, ownership and transient movement effects are server-authoritative. */
public final class IdiotService {
    private static final ResourceLocation SPEED = HabiTrainCore.id("idiot_speed");
    private static final ResourceLocation SLOW = HabiTrainCore.id("idiot_slow");
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> PROTECTION = new HashMap<>(), SLOWED = new HashMap<>();
    private static final Map<UUID, IdiotEggEntity> EGGS = new LinkedHashMap<>();
    private static final Map<UUID, IdiotChickEntity> CHICKS = new LinkedHashMap<>();
    private static UUID round = UUID.randomUUID();
    private static boolean registered;
    private static final class State {
        final IdiotRules.Luck luck = new IdiotRules.Luck();
        long nextThrow, boostUntil;
        int lastBoost = -1, lastPity = -1;
        boolean equipped;
    }
    private IdiotService() {}

    public static void init() {
        if (registered) return;
        registered = true;
        FabricDefaultAttributeRegistry.register(IdiotContent.CHICK, Chicken.createAttributes());
        IdiotEffectPayload.register();
        MatchEvents.STARTED.register(level -> reset(level.getServer()));
        MatchEvents.ROUND_ENDED.register((level, settlement) -> reset(level.getServer()));
        ServerTickEvents.END_SERVER_TICK.register(IdiotService::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearPlayer(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(IdiotService::reset);
    }

    public static RoleHooks hooks() {
        return RoleHooks.builder().lifecycle(new RoleLifecycleHooks() {
            @Override public void onLost(ServerPlayer player, RoleHookContext ctx) { clearPlayer(player); }
        }).combat(new RoleCombatHooks() {
            @Override public void onAnyDeath(ServerPlayer dead, ResourceLocation reason, RoleHookContext ctx) {
                clearPlayer(dead);
            }
        }).build();
    }

    public static boolean isEgg(ItemStack stack) { return stack != null && stack.is(IdiotContent.EGG); }
    public static boolean boundTo(ItemStack stack, Player player) {
        if (!isEgg(stack)) return false;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID("idiotOwner") && tag.getUUID("idiotOwner").equals(player.getUUID())
                && tag.hasUUID("idiotRound") && tag.getUUID("idiotRound").equals(round);
    }
    private static void bind(ItemStack stack, Player player) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("idiotOwner", player.getUUID()); tag.putUUID("idiotRound", round);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.setCount(1);
    }
    private static boolean participant(Player player) {
        return player != null && MatchStateApi.phase(player.level()) == MatchPhase.ACTIVE
                && GameUtils.isPlayerAliveAndSurvival(player);
    }
    private static boolean active(Player player) {
        return participant(player) && HabiRoles.isHabiRole(player, HabiRoles.IDIOT_ID);
    }
    private static long now(Player player) { return player.getServer().getTickCount(); }

    public static boolean throwEgg(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer self) || !active(self) || !boundTo(stack, self)) return false;
        State state = STATES.computeIfAbsent(self.getUUID(), ignored -> new State());
        long tick = now(self);
        if (tick < state.nextThrow || EGGS.size() >= IdiotRules.ROUND_EGGS
                || EGGS.values().stream().filter(e -> e.getOwner() == self).count() >= IdiotRules.OWNER_EGGS) return false;
        IdiotEggEntity egg = new IdiotEggEntity(IdiotContent.PROJECTILE, self.level());
        egg.setOwner(self);
        egg.setPos(self.getX(), self.getEyeY() - .1, self.getZ());
        egg.shootFromRotation(self, self.getXRot(), self.getYRot(), 0, 1.5F, 1F);
        if (!self.serverLevel().addFreshEntity(egg)) return false;
        EGGS.put(egg.getUUID(), egg);
        state.nextThrow = tick + IdiotRules.THROW_TICKS;
        self.getCooldowns().addCooldown(IdiotContent.EGG, IdiotRules.THROW_TICKS);
        self.level().playSound(null, self.blockPosition(), SoundEvents.EGG_THROW, SoundSource.PLAYERS, .45F,
                .9F + self.getRandom().nextFloat() * .2F);
        return true;
    }

    public static boolean valid(IdiotEggEntity egg) {
        return EGGS.get(egg.getUUID()) == egg && egg.getOwner() instanceof ServerPlayer owner
                && owner.level() == egg.level() && active(owner);
    }
    public static boolean valid(IdiotChickEntity chick) {
        if (CHICKS.get(chick.getUUID()) != chick || chick.owner() == null) return false;
        ServerPlayer owner = chick.getServer().getPlayerList().getPlayer(chick.owner());
        return owner != null && owner.level() == chick.level() && active(owner);
    }
    public static void impact(IdiotEggEntity egg, HitResult hit) {
        if (!valid(egg)) { egg.discard(); return; }
        ServerPlayer owner = (ServerPlayer) egg.getOwner();
        Vec3 pos = hit.getLocation();
        broadcast(owner, new IdiotEffectPayload(IdiotEffectPayload.SPLASH, pos, 0, 0));
        owner.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, .3F, 1.65F);
        owner.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.SLIME_BLOCK_HIT, SoundSource.PLAYERS, .25F, 1.5F);
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ServerPlayer target
                && target != owner && participant(target)) affect(owner, target);
        State state = STATES.computeIfAbsent(owner.getUUID(), ignored -> new State());
        boolean hatch = state.luck.wouldHatch(owner.getRandom().nextDouble()) && hatch(owner, pos);
        state.luck.record(hatch);
        sendState(owner, state, true);
        EGGS.remove(egg.getUUID());
        egg.discard();
    }

    private static void affect(ServerPlayer owner, ServerPlayer target) {
        long tick = now(target);
        if (tick < PROTECTION.getOrDefault(target.getUUID(), 0L)) return;
        PROTECTION.put(target.getUUID(), tick + IdiotRules.HIT_PROTECTION_TICKS);
        SLOWED.put(target.getUUID(), tick + IdiotRules.SLOW_TICKS);
        // Independent transient modifiers can be removed without deleting another role's effects.
        modifier(target, SLOW, target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) ? 0 : -.15);
        Vec3 direction = target.position().subtract(owner.position()).multiply(1, 0, 1).normalize().scale(.10);
        target.setDeltaMovement(target.getDeltaMovement().add(direction));
        target.hurtMarked = true;
        send(target, new IdiotEffectPayload(IdiotEffectPayload.FACE, Vec3.ZERO, 0, 0));
        owner.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .15F, 1.6F);
    }

    private static boolean hatch(ServerPlayer owner, Vec3 pos) {
        prune();
        List<IdiotChickEntity> own = CHICKS.values().stream().filter(c -> owner.getUUID().equals(c.owner())).toList();
        if (own.size() >= IdiotRules.OWNER_CHICKS) retire(own.getFirst());
        if (CHICKS.size() >= IdiotRules.ROUND_CHICKS) retire(CHICKS.values().iterator().next());
        IdiotChickEntity chick = new IdiotChickEntity(IdiotContent.CHICK, owner.level());
        chick.owner(owner.getUUID());
        chick.moveTo(pos.x, pos.y + .05, pos.z, owner.getRandom().nextFloat() * 360, 0);
        chick.setCustomName(Component.translatable("entity.habitrain_core.idiot_chick.name." + owner.getRandom().nextInt(3)));
        chick.setCustomNameVisible(false);
        if (!owner.serverLevel().addFreshEntity(chick)) return false;
        CHICKS.put(chick.getUUID(), chick);
        State state = STATES.get(owner.getUUID());
        state.boostUntil = IdiotRules.refreshBoost(state.boostUntil, now(owner));
        updateSpeed(owner, state);
        broadcast(owner, new IdiotEffectPayload(IdiotEffectPayload.HATCH, chick.position(), 0, 0));
        owner.level().playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHICKEN_AMBIENT, SoundSource.PLAYERS, .65F, 1.8F);
        send(owner, new IdiotEffectPayload(IdiotEffectPayload.JACKPOT, Vec3.ZERO, IdiotRules.BOOST_TICKS, 0));
        return true;
    }

    public static void poof(IdiotChickEntity chick) {
        if (!(chick.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        level.sendParticles(ParticleTypes.POOF, chick.getX(), chick.getY() + .2, chick.getZ(), 5, .15, .12, .15, .01);
        level.playSound(null, chick.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, .15F, 1.8F);
    }
    private static void retire(IdiotChickEntity chick) { poof(chick); chick.discard(); CHICKS.remove(chick.getUUID()); }
    private static void prune() {
        EGGS.values().removeIf(Entity::isRemoved);
        CHICKS.values().removeIf(Entity::isRemoved);
    }

    private static void tick(MinecraftServer server) {
        prune();
        long tick = server.getTickCount();
        PROTECTION.values().removeIf(until -> until <= tick);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (SLOWED.containsKey(id)) {
                if (SLOWED.get(id) <= tick || !participant(player)) {
                    modifier(player, SLOW, 0); SLOWED.remove(id);
                } else modifier(player, SLOW, player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) ? 0 : -.15);
            } else modifier(player, SLOW, 0); // also removes stale saved modifiers after restart
            if (active(player)) {
                State state = STATES.computeIfAbsent(id, ignored -> new State());
                if (tick % 5 == 0 || !state.equipped) { ensureEgg(player); state.equipped = true; }
                updateSpeed(player, state);
                if (tick % 10 == 0) sendState(player, state, false);
            } else {
                State state = STATES.get(id);
                if (state != null && state.equipped) clearPlayer(player);
                modifier(player, SPEED, 0);
                if (tick % 5 == 0 && MatchStateApi.phase(player.level()) != MatchPhase.INITIATING
                        && MatchStateApi.phase(player.level()) != MatchPhase.STARTING) removeEggs(player);
            }
        }
    }

    private static void updateSpeed(ServerPlayer player, State state) {
        var vanilla = player.getEffect(MobEffects.MOVEMENT_SPEED);
        modifier(player, SPEED, now(player) < state.boostUntil
                ? IdiotRules.speedMultiplier(vanilla == null ? -1 : vanilla.getAmplifier()) : 0);
    }
    private static void modifier(ServerPlayer player, ResourceLocation id, double amount) {
        var attribute = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) return;
        var previous = attribute.getModifier(id);
        if (previous != null && Math.abs(previous.amount() - amount) < .000001) return;
        attribute.removeModifier(id);
        if (amount != 0) attribute.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void ensureEgg(ServerPlayer player) {
        boolean found = false, changed = false;
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) stacks.add(player.getInventory().getItem(i));
        stacks.add(player.containerMenu.getCarried());
        for (ItemStack stack : stacks) {
            if (!isEgg(stack)) continue;
            CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (!found && (boundTo(stack, player) || !tag.contains("idiotOwner"))) {
                if (!boundTo(stack, player) || stack.getCount() != 1) { bind(stack, player); changed = true; }
                found = true;
            } else { stack.setCount(0); changed = true; }
        }
        if (!found) {
            ItemStack egg = new ItemStack(IdiotContent.EGG); bind(egg, player);
            changed |= player.getInventory().add(egg);
        }
        if (changed) { player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); }
    }
    private static void removeEggs(ServerPlayer player) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isEgg(player.getInventory().getItem(i))) { player.getInventory().setItem(i, ItemStack.EMPTY); changed = true; }
        }
        if (isEgg(player.containerMenu.getCarried())) { player.containerMenu.setCarried(ItemStack.EMPTY); changed = true; }
        if (changed) { player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); }
    }
    public static void clearPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID id = player.getUUID();
        State state = STATES.get(id);
        if (state != null) { state.boostUntil = 0; state.equipped = false; state.lastBoost = state.lastPity = -1; }
        modifier(player, SPEED, 0); modifier(player, SLOW, 0); SLOWED.remove(id);
        removeEggs(player);
        EGGS.values().removeIf(egg -> { if (egg.getOwner() == player) { egg.discard(); return true; } return false; });
        CHICKS.values().removeIf(chick -> { if (id.equals(chick.owner())) { chick.discard(); return true; } return false; });
        send(player, new IdiotEffectPayload(IdiotEffectPayload.STATE, Vec3.ZERO, 0, 0));
    }
    private static void reset(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) clearPlayer(player);
        EGGS.values().forEach(Entity::discard); CHICKS.values().forEach(Entity::discard);
        EGGS.clear(); CHICKS.clear(); STATES.clear(); SLOWED.clear(); PROTECTION.clear();
        round = UUID.randomUUID();
    }
    private static void sendState(ServerPlayer player, State state, boolean force) {
        int boost = (int) Math.max(0, state.boostUntil - now(player)), pity = state.luck.remaining();
        if (force || boost != state.lastBoost || pity != state.lastPity) {
            send(player, new IdiotEffectPayload(IdiotEffectPayload.STATE, Vec3.ZERO, boost, pity));
            state.lastBoost = boost; state.lastPity = pity;
        }
    }
    private static void send(ServerPlayer player, IdiotEffectPayload payload) {
        if (ServerPlayNetworking.canSend(player, IdiotEffectPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
    private static void broadcast(ServerPlayer owner, IdiotEffectPayload payload) {
        for (ServerPlayer viewer : owner.serverLevel().players()) {
            if (viewer.distanceToSqr(payload.position()) <= 32 * 32) send(viewer, payload);
        }
    }
}
