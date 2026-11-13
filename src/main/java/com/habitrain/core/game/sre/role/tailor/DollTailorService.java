package com.habitrain.core.game.sre.role.tailor;

import com.habitrain.core.api.match.replay.LastMatchReplay.Kind;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import com.habitrain.core.network.DollProtectionPayload;
import com.habitrain.core.network.DollTailorActionPayload;
import com.habitrain.core.network.DollTailorStatePayload;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.item.KnifeItem;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.KillerKnifeDurability;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Ephemeral, server-thread-only round state. Nothing here is persisted across matches. */
public final class DollTailorService {
    public static final EntityType<TailorDollEntity> DOLL = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            HabiTrainCore.id("tailor_doll"), EntityType.Builder.of(TailorDollEntity::new, MobCategory.MISC)
                    .sized(.6F, 1.8F).clientTrackingRange(64).updateInterval(2).fireImmune().noSave()
                    .build("habitrain_core:tailor_doll"));
    private static final TicketType<UUID> DOLL_TICKET = TicketType.create("habitrain_tailor", Comparator.comparing(UUID::toString));
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final class State {
        final ServerPlayer player;
        final Map<UUID, Integer> patterns = new HashMap<>();
        UUID selected;
        TailorDollEntity doll;
        Vec3 anchor;
        float yaw;
        long lastRequest = -100;
        State(ServerPlayer player) { this.player = player; }
    }
    private DollTailorService() {}

    public static void init() {
        FabricDefaultAttributeRegistry.register(DOLL, TailorDollEntity.attributes());
        DollTailorActionPayload.register(); DollTailorStatePayload.register(); DollProtectionPayload.register();
        ServerPlayNetworking.registerGlobalReceiver(DollTailorActionPayload.TYPE, (payload, ctx) -> handleAction(ctx.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(DollTailorService::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendLinks(handler.player, false));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> releasePlayer(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (State state : List.copyOf(STATES.values())) clear(state.player);
            STATES.clear();
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isCasting(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                isCasting(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> isCasting(player)
                ? InteractionResultHolder.fail(player.getItemInHand(hand)) : InteractionResultHolder.pass(player.getItemInHand(hand)));
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                isCasting(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isCasting(player) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    static void handleAction(ServerPlayer self, DollTailorActionPayload payload) {
        if (!eligible(self)) return;
        State state = STATES.computeIfAbsent(self.getUUID(), id -> new State(self));
        long now = self.level().getGameTime();
        if (now - state.lastRequest < 4) return;
        state.lastRequest = now;
        if (payload.target() != null && !isCasting(self)) {
            ServerPlayer target = self.server.getPlayerList().getPlayer(payload.target());
            if (target == null || target == self || !ServerAimTargeting.isAliveParticipant(target)) return;
            state.selected = target.getUUID();
        }
        sendState(state);
    }

    private static boolean eligible(ServerPlayer player) {
        return ServerAimTargeting.isAliveParticipant(player) && HabiRoles.isHabiRole(player, HabiRoles.DOLL_TAILOR_ID);
    }
    public static boolean isCasting(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer)) return false;
        State state = STATES.get(player.getUUID());
        return state != null && state.player == player && state.doll != null && !state.doll.complete();
    }
    public static boolean owns(TailorDollEntity doll) {
        State state = STATES.get(doll.ownerId());
        return state != null && state.doll == doll;
    }

    public static boolean sew(RoleSkill.RoleSkillContext context) {
        ServerPlayer self = context.player();
        if (self == null || !eligible(self)) return false;
        State state = STATES.computeIfAbsent(self.getUUID(), id -> new State(self));
        ServerPlayer target = state.selected == null ? null : self.server.getPlayerList().getPlayer(state.selected);
        if (target == null || target == self || target.level() != self.level() || !ServerAimTargeting.isAliveParticipant(target))
            return reject(self, "choose");
        boolean ready = state.patterns.getOrDefault(target.getUUID(), 0) >= DollTailorRules.OBSERVATION_TICKS;
        if (!ready) return reject(self, "pattern");
        if (state.doll != null) return reject(self, "exists");
        var shop = SREPlayerShopComponent.KEY.get(self);
        if (!DollTailorRules.canSew(true, ready, shop.balance, false, false)) return reject(self, "coins");
        if (!self.onGround() || self.isPassenger() || self.isInWaterOrBubble()) return reject(self, "ground");
        Vec3 point = spawnPoint(self);
        if (point == null) return reject(self, "space");
        TailorDollEntity doll = DOLL.create(self.serverLevel());
        if (doll == null) return false;
        doll.moveTo(point.x, point.y, point.z, self.getYRot(), 0);
        doll.setup(self.getUUID(), target.getUUID(), target.getGameProfile().getName(), self.getYRot());
        state.doll = doll;
        if (!self.serverLevel().addFreshEntity(doll)) { state.doll = null; return false; }
        self.serverLevel().getChunkSource().addRegionTicket(DOLL_TICKET, new ChunkPos(doll.blockPosition()), 2, doll.getUUID());
        shop.setBalance(shop.balance - DollTailorRules.PRICE);
        self.stopUsingItem();
        state.anchor = self.position(); state.yaw = self.getYRot();
        self.connection.teleport(self.getX(), self.getY(), self.getZ(), state.yaw, 45F);
        self.setDeltaMovement(Vec3.ZERO);
        sendState(state);
        return true;
    }
    private static boolean reject(ServerPlayer self, String reason) {
        self.displayClientMessage(Component.translatable("message.habitrain_core.doll_tailor." + reason), true);
        return false;
    }
    private static Vec3 spawnPoint(ServerPlayer self) {
        double angle = Math.toRadians(self.getYRot());
        Vec3 forward = self.position().add(-Math.sin(angle) * 1.6, 0, Math.cos(angle) * 1.6);
        var floor = self.level().clip(new ClipContext(forward.add(0, 1, 0), forward.add(0, -2, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        if (floor.getType() == HitResult.Type.MISS || floor.getDirection() != net.minecraft.core.Direction.UP) return null;
        Vec3 point = floor.getLocation().add(0, .01, 0);
        AABB box = new AABB(point.x - .3, point.y, point.z - .3, point.x + .3, point.y + 1.8, point.z + .3);
        return self.level().noCollision(box) && self.level().getWorldBorder().isWithinBounds(BlockPos.containing(point))
                && self.level().clip(new ClipContext(self.getEyePosition(), point.add(0, .9, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS ? point : null;
    }

    private static void tick(MinecraftServer server) {
        for (State state : List.copyOf(STATES.values())) {
            ServerPlayer self = state.player;
            if (server.getPlayerList().getPlayer(self.getUUID()) != self || !eligible(self)) { clear(self); continue; }
            TailorDollEntity doll = state.doll;
            if (doll != null) {
                ServerPlayer target = server.getPlayerList().getPlayer(doll.targetId());
                if (doll.isRemoved() || target == null || !ServerAimTargeting.isAliveParticipant(target)
                        || self.level() != doll.level() || target.level() != doll.level()) {
                    destroy(doll, false); doll = null;
                }
            }
            if (doll != null && !doll.complete()) {
                if (self.isPassenger() || self.position().distanceToSqr(state.anchor) > .04) {
                    interrupt(self); continue;
                }
                self.setPos(state.anchor); self.setYRot(state.yaw); self.setXRot(45F);
                self.setYHeadRot(state.yaw); self.setDeltaMovement(Vec3.ZERO);
                if (doll.stitches() % 4 == 0) self.swing(doll.stitches() % 8 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, true);
                doll.stitches(doll.stitches() + 1);
                if (doll.complete()) {
                    doll.level().playSound(null, doll.blockPosition(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1F, 1.2F);
                    doll.level().playSound(null, doll.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .6F, 1.5F);
                    broadcastLinks(self.serverLevel());
                    sendState(state);
                }
            } else if (state.selected != null) {
                ServerPlayer target = server.getPlayerList().getPlayer(state.selected);
                if (target != null && target != self && target.level() == self.level() && ServerAimTargeting.isAliveParticipant(target)) {
                    int before = state.patterns.getOrDefault(state.selected, 0);
                    int after = DollTailorRules.observe(before,
                            self.distanceToSqr(target) <= DollTailorRules.OBSERVATION_RANGE * DollTailorRules.OBSERVATION_RANGE,
                            DollTailorRules.canObserve(self, target));
                    if (before != after) {
                        state.patterns.put(state.selected, after);
                        if (self.tickCount % 8 == 0) {
                            Vec3 p = self.getEyePosition().lerp(target.getEyePosition(), .5);
                            self.serverLevel().sendParticles(self, ParticleTypes.SNOWFLAKE, false, p.x, p.y, p.z, 3, .12, .1, .12, .01);
                        }
                        if (after == DollTailorRules.OBSERVATION_TICKS) {
                            self.playNotifySound(SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, .8F, 1.5F);
                            self.displayClientMessage(Component.translatable("message.habitrain_core.doll_tailor.ready", target.getDisplayName()), true);
                            sendState(state);
                        }
                    }
                }
            }
            if (self.tickCount % 5 == 0) sendState(state);
            if (self.tickCount % 20 == 0) sendLinks(self, false);
        }
        // Refresh dimension changes and late joins even when the viewer is not a tailor.
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.tickCount % 40 == 0) sendLinks(viewer, false);
        }
    }

    public static void interrupt(ServerPlayer self) {
        if (!isCasting(self)) return;
        State state = STATES.get(self.getUUID());
        destroy(state.doll, false);
        self.displayClientMessage(Component.translatable("message.habitrain_core.doll_tailor.interrupted"), true);
        sendState(state);
    }
    public static void releasePlayer(ServerPlayer player) {
        clear(player);
        for (State state : List.copyOf(STATES.values())) {
            if (state.doll != null && player.getUUID().equals(state.doll.targetId())) destroy(state.doll, false);
        }
    }
    public static void clear(ServerPlayer self) {
        State state = STATES.remove(self.getUUID());
        if (state == null) return;
        if (state.doll != null) destroy(state.doll, false);
        if (!self.hasDisconnected()) ServerPlayNetworking.send(self, new DollTailorStatePayload(null, 0, List.of()));
    }
    public static void destroy(TailorDollEntity doll, boolean broken) {
        if (doll == null) return;
        State state = STATES.get(doll.ownerId());
        if (state != null && state.doll == doll) { state.doll = null; state.anchor = null; }
        if (doll.level() instanceof ServerLevel level) {
            level.getChunkSource().removeRegionTicket(DOLL_TICKET, new ChunkPos(doll.blockPosition()), 2, doll.getUUID());
            if (broken) {
                MatchReplayRecorder.spatial(level, Kind.SHIELD_BROKEN, null,
                        doll.targetId(), null, doll.position(), "habitrain_core:tailor_doll", 0);
                doll.burst();
            }
            doll.discard();
            for (ServerPlayer viewer : level.players()) {
                boolean snapped = broken && (viewer.getUUID().equals(doll.ownerId()) || viewer.getUUID().equals(doll.targetId()));
                sendLinks(viewer, snapped);
                if (snapped) {
                    viewer.playNotifySound(SoundEvents.TRIPWIRE_CLICK_OFF, SoundSource.PLAYERS, 1F, 1.7F);
                    viewer.displayClientMessage(Component.translatable("message.habitrain_core.doll_tailor.broken"), true);
                }
            }
        }
        if (state != null) sendState(state);
    }
    private static void sendState(State state) {
        if (state.player.hasDisconnected()) return;
        List<DollTailorStatePayload.Entry> entries = state.player.server.getPlayerList().getPlayers().stream()
                .filter(p -> p != state.player && ServerAimTargeting.isAliveParticipant(p))
                .limit(256).map(p -> new DollTailorStatePayload.Entry(p.getUUID(), p.getGameProfile().getName(),
                        state.patterns.getOrDefault(p.getUUID(), 0))).toList();
        int ticks = state.doll != null && !state.doll.complete() ? state.doll.stitches() + 1 : 0;
        ServerPlayNetworking.send(state.player, new DollTailorStatePayload(state.selected, ticks, entries));
    }
    private static void broadcastLinks(ServerLevel level) { for (ServerPlayer viewer : level.players()) sendLinks(viewer, false); }
    private static void sendLinks(ServerPlayer viewer, boolean snapped) {
        List<DollProtectionPayload.Link> links = STATES.values().stream().map(s -> s.doll)
                .filter(d -> d != null && !d.isRemoved() && d.complete() && d.level() == viewer.level()).limit(256)
                .map(d -> new DollProtectionPayload.Link(d.ownerId(), d.targetId(), d.getUUID(), d.position())).toList();
        ServerPlayNetworking.send(viewer, new DollProtectionPayload(links, snapped));
    }

    /** Called only for the doll branch of SRE's knife packet. Client IDs are not trusted. */
    public static void knifeHit(ServerPlayer attacker, TailorDollEntity doll) {
        if (!ServerAimTargeting.isAliveParticipant(attacker) || isCasting(attacker) || !doll.complete()
                || !owns(doll) || attacker.distanceToSqr(doll) > 16 || !attacker.hasLineOfSight(doll)) return;
        var knife = attacker.getMainHandItem();
        if (!(knife.getItem() instanceof KnifeItem) || attacker.getCooldowns().isOnCooldown(knife.getItem())
                || KillerKnifeDurability.isDepleted(knife)) return;
        Vec3 start = attacker.getEyePosition();
        if (doll.getBoundingBox().inflate(.15).clip(start, start.add(attacker.getLookAngle().scale(4))).isEmpty()) return;
        destroy(doll, true);
        if (KillerKnifeDurability.isDurabilityModeEnabled(attacker.level()) && KillerKnifeDurability.isMarkedKnife(knife))
            KillerKnifeDurability.consumeOne(knife, attacker);
        attacker.swing(InteractionHand.MAIN_HAND);
        if (!attacker.isCreative()) attacker.getCooldowns().addCooldown(knife.getItem(),
                GameConstants.ITEM_COOLDOWNS.getOrDefault(knife.getItem(), 600));
    }

    /** The upstream gun handler has already consumed ammunition and checked its cooldown. */
    public static void gunHit(ServerPlayer attacker, int entityId) {
        if (!attacker.getMainHandItem().is(io.wifi.starrailexpress.index.tag.TMMItemTags.GUNS)) return;
        rangedHit(attacker, entityId, 20);
    }

    public static void rangedHit(ServerPlayer attacker, int entityId, double range) {
        if (!(attacker.serverLevel().getEntity(entityId) instanceof TailorDollEntity doll)
                || !ServerAimTargeting.isAliveParticipant(attacker) || isCasting(attacker)
                || !doll.complete() || !owns(doll) || attacker.distanceToSqr(doll) > range * range
                || !attacker.hasLineOfSight(doll)) return;
        Vec3 eye = attacker.getEyePosition();
        if (doll.getBoundingBox().inflate(.1).clip(eye, eye.add(attacker.getLookAngle().scale(range))).isPresent()) destroy(doll, true);
    }
}
