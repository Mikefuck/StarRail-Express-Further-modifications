package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.game.sre.match.MatchEventBridge;
import com.habitrain.core.game.sre.match.MvpScoreTracker;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import io.wifi.starrailexpress.api.replay.event.ReplayEventRecordedCallback;
import io.wifi.starrailexpress.cca.*;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnRevolverUsed;
import io.wifi.starrailexpress.event.OnShieldBroken;
import io.wifi.starrailexpress.content.block.SmallDoorBlock;
import io.wifi.starrailexpress.index.TMMItems;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import io.wifi.starrailexpress.game.GameUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.init.ModEffects;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/** Lives on the server thread; upstream callbacks record positions before game state is cleared. */
public final class MatchReplayRecorder {
    private static Round current;
    private MatchReplayRecorder() {}

    public static void register() {
        MatchEvents.STARTED.register(MatchReplayRecorder::begin);
        OnGameTrueStarted.EVENT.register(level -> {
            if (current == null || current.level != level) begin(level);
        });
        MatchEventBridge.WINNERS_FINALIZED.register(MatchReplayRecorder::finish);
        ServerTickEvents.END_WORLD_TICK.register(MatchReplayRecorder::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity.tickCount != 0 || !(entity instanceof Projectile projectile)
                    || !(projectile.getOwner() instanceof ServerPlayer thrower)) return;
            var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if ((type.getNamespace().equals("starrailexpress") || type.getNamespace().equals("noellesroles"))
                    && type.getPath().contains("grenade")) {
                spatial(level, Kind.GRENADE_THROWN, thrower.getUUID(), null, entity.position(), null, type.toString(), 0);
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerLevel level && !player.isSpectator() && player.getItemInHand(hand).is(TMMItems.LOCKPICK)) {
                BlockPos pos = hit.getBlockPos();
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof SmallDoorBlock && !player.isShiftKeyDown()) {
                    if (state.getValue(SmallDoorBlock.HALF) == DoubleBlockHalf.UPPER) pos = pos.below();
                    spatial(level, Kind.LOCKPICK_ATTEMPT, player.getUUID(), null, player.position(),
                            Vec3.atCenterOf(pos), "attempt", 0);
                }
            }
            return InteractionResult.PASS;
        });
        OnPlayerDeathWithKiller.EVENT.register(MatchReplayRecorder::death);
        OnRevolverUsed.EVENT.register((player, target) -> shot(player, target, 30, true));
        OnShieldBroken.EVENT.register((victim, attacker) -> {
            if (victim.level() instanceof ServerLevel level) spatial(level, Kind.SHIELD_BROKEN, id(attacker), id(victim),
                    attacker == null ? null : attacker.position(), victim.position(), "", SUCCESS);
        });
        ReplayEventRecordedCallback.EVENT.register((event, unused) -> fromUpstream(event));
    }

    public static void beforeBlockWrite(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, BlockPos pos) {
        Round r = current;
        if (r != null && r.level == level && !r.captureDone && level.getServer().isSameThread()) r.capture.beforeWrite(chunk, pos);
    }

    public static void reset() {
        if (current != null) current.capture.cancel();
        current = null;
    }

    private static void begin(ServerLevel level) {
        var game = SREGameWorldComponent.KEY.get(level);
        if (game == null || game.getGameStatus() == SREGameWorldComponent.GameStatus.INACTIVE) return;
        if (current != null && current.level == level && current.started == level.getGameTime()) return;
        reset();
        var areas = AreasWorldComponent.KEY.get(level);
        SceneBounds bounds = ReplaySectionGeometry.playBounds(areas.getPlayArea(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        String mapKey = bounded(areas.mapName, 128);
        String label = bounded(areas.mapDisplayName, 128);
        current = new Round(level, bounds, mapKey, label.isBlank() ? mapKey : label);
        if (game.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE) current.activate(game);
    }

    private static void tick(ServerLevel level) {
        Round r = current;
        if (r == null || r.level != level) return;
        var game = SREGameWorldComponent.KEY.get(level);
        if (game.getGameStatus() == SREGameWorldComponent.GameStatus.INACTIVE) {
            reset();
            return;
        }
        if (!r.captureDone) {
            try { if (r.capture.step()) r.finishMap(); }
            catch (RuntimeException failure) { r.failMap(failure); }
        }
        if (game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) {
            if (r.start >= 0 && r.activeEnd < 0) r.activeEnd = level.getGameTime();
            return;
        }
        if (r.start < 0) r.activate(game);
        int at = r.now();
        boolean dark = SREWorldBlackoutComponent.KEY.get(level).isBlackoutActive();
        r.environment(SpanKind.BLACKOUT, null, dark, at, null);
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            if (!r.eligible(id)) continue;
            boolean psycho = GameUtils.isPlayerAliveAndSurvival(player)
                    && SREPlayerPsychoComponent.KEY.get(player).getPsychoTicks() > 0;
            r.environment(SpanKind.PSYCHO, id, psycho, at, player.position());
        }
        if (at != r.lastSample && at % 5 == 0) r.sample(game, at);
    }

    private static void finish(ServerLevel level, MatchSettlement settlement) {
        Round r = current;
        if (r == null || r.level != level) return;
        current = null;
        if (r.start < 0 || settlement.participants().isEmpty()) {
            r.capture.cancel();
            return;
        }
        if (!r.captureDone) {
            try { r.finishMap(); }
            catch (RuntimeException failure) { r.failMap(failure); }
        }
        var game = SREGameWorldComponent.KEY.get(level);
        var cca = SREGameRoundEndComponent.KEY.get(level);
        var scores = MvpScoreTracker.snapshot(level);
        Map<UUID, String> names = new HashMap<>();
        if (cca.players != null) for (var p : cca.players) {
            if (p != null && p.player != null) names.put(p.player.getId(), p.player.getName());
        }
        List<UUID> ids = settlement.participants().stream().filter(id -> !RepairModeManager.isRepairer(id))
                .filter(id -> {
                    SRERole role = game.getRole(id);
                    return role == null || !excluded(role);
                })
                .sorted(Comparator.<UUID, Boolean>comparing(settlement.winners()::contains).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        int at = r.now();
        List<Participant> people = new ArrayList<>();
        for (UUID id : ids) {
            if (people.size() >= MAX_PLAYERS) { r.limited = true; break; }
            Person person = r.people.get(id);
            var score = scores.get(id);
            Role end = role(game.getRole(id));
            if (end.id.isEmpty() && person != null) end = person.lastRole;
            Role start = person != null ? person.startRole : end;
            String name = person != null ? person.name : names.getOrDefault(id, score == null ? "" : score.playerName());
            people.add(new Participant(id, bounded(name, 64), start.id, start.color, end.id, end.color,
                    settlement.factionOfOrEmpty(id).orElse(MatchWinFaction.PASSENGER), settlement.winners().contains(id),
                    score == null ? 0 : score.survivalSeconds(), score == null ? 0 : score.kills(),
                    score == null ? 0 : score.score(), score == null ? 0 : score.itemUses(),
                    person == null ? List.of() : person.track));
        }
        if (people.isEmpty()) return;
        for (var entry : r.open.entrySet()) r.close(entry.getKey(), entry.getValue(), at);
        UUID mvp = people.stream().filter(p -> p.winner() && scores.containsKey(p.id())).min(
                Comparator.comparingInt(Participant::score).reversed()
                        .thenComparing(Comparator.comparingInt(Participant::kills).reversed())
                        .thenComparing(Comparator.comparingInt(Participant::survivalSeconds).reversed())
                        .thenComparing(Comparator.comparingInt(Participant::itemUses).reversed())
                        .thenComparing(Participant::id)).map(Participant::id).orElse(null);
        long anchor = level.getServer().overworld().getGameTime();
        var win = cca.getWinStatus();
        r.events.sort(Comparator.comparingInt(Node::tick));
        r.spans.sort(Comparator.comparingInt(Span::startTick));
        ReplayBoardService.publish(new LastMatchReplay(UUID.randomUUID(), anchor, at, r.label, bounded(settlement.modeId(), 128),
                win == null ? "NONE" : win.name(), settlement.winKind(),
                bounded(cca.CustomWinnerID, 128), title(cca.CustomWinnerTitle, level), title(cca.CustomWinnerSubtitle, level),
                cca.CustomWinnerColor, r.bounds, null, people, r.events, r.spans, mvp, r.kills, r.limited), r.map);
    }

    private static String title(Component c, ServerLevel level) {
        if (c == null) return "";
        String json = Component.Serializer.toJson(c, level.registryAccess());
        return json.length() <= 2048 ? json : Component.Serializer.toJson(
                Component.literal(bounded(c.getString(), 256)), level.registryAccess());
    }

    private static void death(Player victim, @Nullable Player killer, ResourceLocation reason) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        Round r = running(level);
        if (r == null || !r.eligible(victim.getUUID())) return;
        String key = reason == null ? "" : reason.toString();
        if (r.deaths.getOrDefault(victim.getUUID(), -1) != r.now()) {
            if (r.node(Kind.KILL, id(killer), victim.getUUID(), killer == null ? null : killer.position(), victim.position(), key, 0)) r.kills++;
            r.deaths.put(victim.getUUID(), r.now());
        }
        r.environment(SpanKind.PSYCHO, victim.getUUID(), false, r.now(), victim.position());
        if (reason != null && reason.getPath().contains("backfire")) {
            r.recordBackfire(victim.getUUID(), r.pos(victim.position()));
        }
        if (reason != null && reason.getPath().equals("poison")) {
            r.node(Kind.POISON, id(killer), victim.getUUID(), null, victim.position(), "triggered", SUCCESS);
        }
        Person p = r.people.get(victim.getUUID());
        Position at = r.pos(victim.position());
        if (p != null && at != null) ReplayTracks.append(p.track, new Sample(r.now(), at, yaw(victim), (byte) 0));
    }

    public static void shot(ServerPlayer shooter, @Nullable Entity hit, double range, boolean authoritative) {
        Round r = running(shooter.serverLevel());
        if (r == null || !r.eligible(shooter.getUUID())) return;
        UUID who = shooter.getUUID();
        int at = r.now();
        Shot previous = r.shots.get(who);
        if (previous != null && previous.tick == at && previous.authoritative && !authoritative) return;
        Vec3 from = shooter.getEyePosition();
        Backfire backfire = r.backfires.get(who);
        boolean misfired = backfire != null && backfire.tick == at;
        Vec3 to;
        if (misfired) {
            hit = null;
            to = null;
        } else if (hit != null && hit.distanceToSqr(shooter) <= range * range) {
            to = hit.getBoundingBox().getCenter();
        } else {
            hit = null;
            Vec3 end = from.add(shooter.getViewVector(1).scale(range));
            to = shooter.serverLevel().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, shooter)).getLocation();
        }
        int flags = hit == null ? 0 : HIT;
        Node node = new Node(at, Kind.GUNSHOT, who, hit instanceof Player p ? p.getUUID() : null,
                r.pos(from), r.pos(to), bounded(BuiltInRegistries.ITEM.getKey(shooter.getMainHandItem().getItem()).toString(), 128), flags);
        if (misfired) node = backfireShot(node, backfire.impact);
        int index = r.add(node);
        if (index >= 0) r.shots.put(who, new Shot(at, index, authoritative));
    }

    public static void gunDrop(ServerPlayer shooter) {
        Round r = running(shooter.serverLevel());
        if (r != null) r.markShot(shooter.getUUID(), GUN_DROP);
    }

    public static void spatial(ServerLevel level, Kind kind, UUID actor, UUID target, Vec3 from, Vec3 to,
                               String detail, int flags) {
        Round r = running(level);
        if (r == null || !level.getServer().isSameThread()
                || (actor != null || target != null) && !r.eligible(actor) && !r.eligible(target)) return;
        r.node(kind, actor, target, from, to, detail, flags);
    }

    static @Nullable Kind publicEventKind(ReplayTimelineEvent e) {
        if (e.hidden()) return null;
        return switch (e.type().name()) {
            case "PLAYER_KILL" -> null; // upstream logs an attempt before shield/allowDeath checks
            case "PLAYER_POISONED" -> Kind.POISON;
            case "ARMOR_BREAK" -> Kind.SHIELD_BROKEN;
            case "BLACKOUT_START", "BLACKOUT_END", "PSYCHO_STATE_CHANGE" -> null; // live component edges own these
            case "BOMB_DETONATE" -> null; // detonateAt supplies the true center
            default -> {
                try { yield Kind.valueOf(e.type().name()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private static void fromUpstream(ReplayTimelineEvent e) {
        Round r = current;
        if (r == null || !r.level.getServer().isSameThread()) return;
        Kind kind = publicEventKind(e);
        if (kind == null) return;
        if (running(r.level) == null) return;
        UUID actor = e.actor() == null ? null : e.actor().uuid(), target = e.target() == null ? null : e.target().uuid();
        if ((actor != null || target != null) && !r.eligible(actor) && !r.eligible(target)) return;
        Map<String, String> data = e.data();
        String item = data.getOrDefault("itemUsed", "");
        String message = data.getOrDefault("message", "");
        Vec3 from = r.position(actor), to = r.position(target);
        String detail = item.isBlank() ? message : item;
        int flags = 0;
        switch (kind) {
            case PLAYER_REVIVAL -> {
                detail = message;
                r.deaths.remove(actor);
            }
            case CHANGE_ROLE -> detail = message;
            case DOOR_PRY, DOOR_SEAL, LOCKPICK_ATTEMPT, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE, GRENADE_THROWN -> {
                try { to = Vec3.atCenterOf(BlockPos.of(Long.parseLong(item))); }
                catch (NumberFormatException ignored) { flags |= APPROXIMATE; }
                if ("true".equalsIgnoreCase(message)) flags |= SUCCESS;
            }
            case BOMB_DEFUSE -> {
                if (target == null) return; // C4Detonation records successful block defusals with charge coordinates.
                if (to == null) flags |= APPROXIMATE;
                else flags |= SUCCESS;
            }
            case POISON -> detail = "applied";
            default -> {}
        }
        r.node(kind, actor, target, from, to, detail, flags);
    }

    private static @Nullable Round running(ServerLevel level) {
        Round r = current;
        if (r == null || r.level != level || r.activeEnd >= 0 || !level.getServer().isSameThread()) return null;
        if (r.start < 0) {
            var game = SREGameWorldComponent.KEY.get(level);
            if (game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return null;
            r.activate(game);
        }
        return r;
    }

    private static @Nullable UUID id(@Nullable Entity entity) { return entity == null ? null : entity.getUUID(); }
    private static byte yaw(Player player) { return (byte) Math.round(player.getYRot() * 256.0f / 360); }
    private static boolean excluded(SRERole role) {
        return role == null || TMMRoles.DISCOVERY_CIVILIAN.identifier().equals(role.identifier());
    }
    private record Role(String id, int color) {}
    private static Role role(@Nullable SRERole raw) {
        if (raw == null || raw.identifier() == null) return new Role("", 0);
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        SRERole value = snapshot == null ? raw : snapshot.find(RoleKey.of(raw.identifier())).map(EffectiveRole::role).orElse(raw);
        return new Role(bounded(value.identifier().toString(), 128), value.color());
    }
    private static String bounded(String text, int max) {
        if (text == null) return "";
        int end = Math.min(text.length(), max);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static final class Person {
        final String name;
        final Role startRole;
        Role lastRole;
        final List<Sample> track = new ArrayList<>();
        boolean present;
        Person(String name, Role role) { this.name = name; this.startRole = role; this.lastRole = role; }
    }
    private record SpanKey(SpanKind kind, @Nullable UUID actor) {}
    private record Shot(int tick, int index, boolean authoritative) {}
    private record Backfire(int tick, @Nullable Position impact) {}

    static Node backfireShot(Node shot, @Nullable Position impact) {
        return new Node(shot.tick(), shot.kind(), shot.actor(), shot.actor(), shot.from(),
                impact == null ? shot.from() : impact, shot.detail(), (shot.flags() & ~HIT) | BACKFIRE);
    }

    private static final class Round {
        final ServerLevel level;
        final long started;
        final SceneBounds bounds;
        final String label;
        final ReplayMapCapture capture;
        final Map<UUID, Person> people = new LinkedHashMap<>();
        final Map<UUID, Integer> deaths = new HashMap<>();
        final Map<UUID, Shot> shots = new HashMap<>();
        final Map<UUID, Backfire> backfires = new HashMap<>();
        final Map<SpanKey, Integer> open = new HashMap<>();
        final List<Node> events = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
        long start = -1, activeEnd = -1;
        int kills, lastSample = -1;
        boolean limited, captureDone;
        java.util.concurrent.CompletableFuture<ReplayMapCapture.Result> map;

        Round(ServerLevel level, SceneBounds bounds, String key, String label) {
            this.level = level; this.started = level.getGameTime(); this.bounds = bounds; this.label = label;
            this.capture = new ReplayMapCapture(level, bounds, key);
        }
        void finishMap() {
            captureDone = true;
            var raw = capture.finish();
            limited |= raw == null || raw.partialBounds() != null;
            if (raw != null) map = ReplayBoardService.encodeMap(raw);
        }
        void failMap(RuntimeException failure) {
            captureDone = true;
            limited = true;
            capture.cancel();
            LoggerFactory.getLogger(MatchReplayRecorder.class).warn("Replay geometry capture failed", failure);
        }
        int now() { return (int) Math.clamp((activeEnd >= 0 ? activeEnd : level.getGameTime()) - start, 0L, MAX_TICKS); }
        void activate(SREGameWorldComponent game) {
            start = level.getGameTime();
            sample(game, 0);
        }
        boolean eligible(UUID id) {
            return id != null && !RepairModeManager.isRepairer(id)
                    && (people.containsKey(id) || !excluded(SREGameWorldComponent.KEY.get(level).getRole(id)));
        }
        void sample(SREGameWorldComponent game, int tick) {
            lastSample = tick;
            var seen = new HashSet<UUID>();
            for (ServerPlayer player : level.players()) {
                UUID id = player.getUUID();
                SRERole raw = game.getRole(player);
                if (excluded(raw) || RepairModeManager.isRepairer(id)) continue;
                if (!people.containsKey(id) && people.size() >= MAX_PLAYERS) { limited = true; continue; }
                Role role = role(raw);
                Person p = people.computeIfAbsent(id, k -> new Person(player.getGameProfile().getName(), role));
                seen.add(id);
                boolean alive = GameUtils.isPlayerAliveAndSurvival(player) && deaths.getOrDefault(id, -1) != tick;
                boolean psycho = alive && SREPlayerPsychoComponent.KEY.get(player).getPsychoTicks() > 0;
                Vec3 position = player.position();
                if (!p.lastRole.equals(role)) {
                    node(Kind.CHANGE_ROLE, id, null, position, null, p.lastRole.id + "===" + role.id, 0);
                    p.lastRole = role;
                }
                Position fixed = pos(position);
                if (fixed == null) { p.present = false; continue; }
                Sample last = p.track.isEmpty() ? null : p.track.getLast();
                if (!alive) {
                    if (last != null && (last.flags() & ALIVE) != 0) ReplayTracks.append(p.track, new Sample(tick, fixed, yaw(player), (byte) 0));
                } else {
                    int flags = ALIVE;
                    if (player.isSprinting()) flags |= SPRINT;
                    if (player.isShiftKeyDown()) flags |= SNEAK;
                    if (player.hasEffect(ModEffects.DISGUISE)) flags |= DISGUISED;
                    if (last != null && ((last.flags() ^ flags) & DISGUISED) != 0) {
                        node(Kind.DISGUISE, id, null, position, null, (flags & DISGUISED) == 0 ? "end" : "start", 0);
                    }
                    if (psycho) flags |= PSYCHO;
                    if (player.isSleeping() || SlothComponent.isSleepingSloth(player)) flags |= ASLEEP;
                    if (last != null && ((last.flags() & ALIVE) == 0 || !p.present
                            || Math.abs(last.position().x() - fixed.x()) > 128
                            || Math.abs(last.position().y() - fixed.y()) > 128
                            || Math.abs(last.position().z() - fixed.z()) > 128)) flags |= BREAK;
                    if (last != null && (last.flags() & ALIVE) == 0 && deaths.remove(id) != null) {
                        node(Kind.PLAYER_REVIVAL, id, null, position, null, role.id, 0);
                    }
                    if (p.track.size() >= ReplayTracks.MAX_TRACK) limited = true;
                    ReplayTracks.append(p.track, new Sample(tick, fixed, yaw(player), (byte) flags));
                }
                p.present = true;
            }
            for (var entry : people.entrySet()) {
                Person p = entry.getValue();
                if (!seen.contains(entry.getKey()) && p.present && !p.track.isEmpty()) {
                    p.present = false;
                    Sample last = p.track.getLast();
                    ReplayTracks.append(p.track, new Sample(tick, last.position(), last.yaw(), BREAK));
                    environment(SpanKind.PSYCHO, entry.getKey(), false, tick, null);
                }
            }
        }
        void environment(SpanKind kind, UUID actor, boolean active, int at, Vec3 pos) {
            SpanKey key = new SpanKey(kind, actor);
            if (active && !open.containsKey(key)) {
                open.put(key, at);
                node(kind == SpanKind.BLACKOUT ? Kind.BLACKOUT_START : Kind.PSYCHO_STATE_CHANGE, actor, null,
                        pos, null, "start", SUCCESS);
            } else if (!active) {
                Integer previous = open.remove(key);
                if (previous != null) {
                    close(key, previous, at);
                    node(kind == SpanKind.BLACKOUT ? Kind.BLACKOUT_END : Kind.PSYCHO_STATE_CHANGE, actor, null,
                            pos, null, "end", 0);
                }
            }
        }
        void close(SpanKey key, int previous, int at) {
            if (spans.size() < MAX_SPANS) spans.add(new Span(key.kind, key.actor, previous, at));
            else limited = true;
        }
        Position pos(Vec3 point) {
            if (point == null) return null;
            return Position.quantize(point.x - bounds.minX(), point.y - bounds.minY(), point.z - bounds.minZ());
        }
        Vec3 position(UUID id) {
            if (id == null) return null;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            return p != null && p.serverLevel() == level ? p.position() : null;
        }
        boolean node(Kind kind, UUID actor, UUID target, Vec3 from, Vec3 to, String detail, int flags) {
            int tick = now();
            Node node = new Node(tick, kind, actor, target, pos(from), pos(to), bounded(detail, 128), flags);
            for (int i = events.size() - 1; i >= Math.max(0, events.size() - 32); i--) {
                Node e = events.get(i);
                if (tick - e.tick() > (kind == Kind.PLAYER_REVIVAL || kind == Kind.CHANGE_ROLE || kind == Kind.DISGUISE ? 5 : 0)) break;
                if (e.kind() != kind || !Objects.equals(e.target(), target)
                        || !Objects.equals(e.actor(), actor) && kind != Kind.SHIELD_BROKEN && kind != Kind.KILL) continue;
                if (kind == Kind.LOCKPICK_ATTEMPT && Objects.equals(e.to(), node.to())) return false;
                if (kind == Kind.SKILL_RELEASE && e.detail().equals(node.detail()) && tick == e.tick()) return false;
                if (kind == Kind.KILL || kind == Kind.POISON || kind == Kind.SHIELD_BROKEN
                        || kind == Kind.CHANGE_ROLE || kind == Kind.PLAYER_REVIVAL || kind == Kind.DISGUISE) {
                    if ((kind == Kind.POISON || kind == Kind.DISGUISE) && !e.detail().equals(node.detail())) {
                        if (kind != Kind.DISGUISE || !e.detail().isBlank()) continue;
                    }
                    if ((e.flags() & APPROXIMATE) != 0 || e.to() == null && node.to() != null) events.set(i, node);
                    return false;
                }
            }
            return add(node) >= 0;
        }
        int add(Node node) {
            if (events.size() == MAX_EVENTS) {
                limited = true;
                if (!node.highlight()) return -1;
                // Reserve room for a late turning point. Existing shot indices become invalid.
                int i = 1;
                while (i < events.size() && events.get(i).highlight()) i++;
                if (i == events.size()) i = 1;
                events.remove(i);
                shots.clear();
            }
            events.add(node);
            return events.size() - 1;
        }
        void recordBackfire(UUID actor, @Nullable Position impact) {
            int tick = now();
            backfires.put(actor, new Backfire(tick, impact));
            Shot shot = shots.get(actor);
            if (shot != null && shot.tick == tick) {
                events.set(shot.index, backfireShot(events.get(shot.index), impact));
            }
        }
        void markShot(UUID actor, int flag) {
            Shot shot = shots.get(actor);
            if (shot == null || now() - shot.tick > 2 || shot.index >= events.size()) return;
            Node node = events.get(shot.index);
            events.set(shot.index, new Node(node.tick(), node.kind(), node.actor(), node.target(),
                    node.from(), node.to(), node.detail(), node.flags() | flag));
        }
    }
}
