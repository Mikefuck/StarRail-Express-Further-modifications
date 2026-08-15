package com.habitrain.core.role.action;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.ActionTargetCodec;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionContext;
import com.habitrain.core.api.role.v2.action.RoleActionDirection;
import com.habitrain.core.api.role.v2.action.RoleActionHandler;
import com.habitrain.core.api.role.v2.action.RoleActionResult;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory {@link RoleActionApi} implementation (fix-doc §12).
 *
 * <p>Server-side validation follows §12.4 in order: exists → direction →
 * spec max bytes → sequence/replay → current role → alive → target (decoder-
 * driven, same world) → distance/LOS → cooldown → rate → handler. Every
 * handler attempt consumes the rate budget; only success consumes cooldown.
 * The server always echoes the request
 * {@code sequence} through the injected {@link ResultSender}; {@code sendTo}
 * pushes go out as {@code push} payloads. Network I/O is injected so unit
 * tests never touch Fabric networking.
 */
public final class RoleActionServiceImpl implements RoleActionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleActionApi");

    /** Sequences more than this many behind the window max are rejected as stale. */
    private static final int STALE_BEHIND = 64;
    /** How many recently-accepted sequences per player/action to remember for replay. */
    private static final int REPLAY_WINDOW = STALE_BEHIND + 1;

    private final Map<ResourceLocation, RoleActionSpec> specs = new LinkedHashMap<>();
    private final Map<GateKey, Deque<Long>> rateWindows = new ConcurrentHashMap<>();
    private final Map<GateKey, Long> lastUseMs = new ConcurrentHashMap<>();
    private final Map<GateKey, Deque<Integer>> seqWindows = new ConcurrentHashMap<>();
    private volatile boolean frozen;
    private volatile Function<ServerPlayer, RoleKey> currentRoleLookup = RoleActionServiceImpl::lookupCurrentRole;
    private volatile Function<ServerPlayer, Boolean> aliveLookup = RoleActionServiceImpl::lookupAlive;
    /**
     * Server-authoritative handshake gate (audit P1-4): a player whose client
     * is missing required providers / incompatible API / stale definition hash
     * is refused role actions with a clear reason. Defaults to allow-all so the
     * service stays testable; core binds {@code RoleHandshakeGate} at startup.
     */
    private volatile Function<ServerPlayer, String> handshakeGate = p -> null;
    private volatile S2CSender s2cSender = (player, id, payload) -> {};
    private volatile ResultSender resultSender = (player, id, seq, ok, reason, payload) -> {};
    private volatile Clock clock = System::currentTimeMillis;

    public RoleActionServiceImpl() {}

    public void setCurrentRoleLookup(Function<ServerPlayer, RoleKey> lookup) {
        this.currentRoleLookup = lookup == null ? p -> null : lookup;
    }

    public void setAliveLookup(Function<ServerPlayer, Boolean> lookup) {
        this.aliveLookup = lookup == null ? p -> Boolean.TRUE : lookup;
    }

    /**
     * Binds the handshake gate (audit P1-4). The function returns a
     * {@code null} reason when the player may act, or a human-readable reason
     * (sent back to the client) when actions must be refused.
     */
    public void setHandshakeGate(Function<ServerPlayer, String> gate) {
        this.handshakeGate = gate == null ? p -> null : gate;
    }

    public void setS2cSender(S2CSender sender) {
        this.s2cSender = sender == null ? (p, i, b) -> {} : sender;
    }

    /** Binds the response channel for C2S results (echoes the request sequence). */
    public void setResultSender(ResultSender sender) {
        this.resultSender = sender == null ? (p, i, s, ok, r, b) -> {} : sender;
    }

    public void setClock(Clock clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** DISCONNECT cleanup: drops the player's sequence windows, rate and cooldown state. */
    public void onPlayerDisconnect(@Nullable UUID playerId) {
        if (playerId == null) {
            return;
        }
        seqWindows.keySet().removeIf(k -> playerId.equals(k.playerId()));
        rateWindows.keySet().removeIf(k -> playerId.equals(k.playerId()));
        lastUseMs.keySet().removeIf(k -> playerId.equals(k.playerId()));
    }

    @Override
    public synchronized RoleActionSpec register(RoleActionSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (frozen) {
            throw new IllegalStateException("Role action registry is frozen");
        }
        if (specs.containsKey(spec.id())) {
            throw new IllegalArgumentException("Duplicate role action: " + spec.id());
        }
        specs.put(spec.id(), spec);
        LOGGER.info("Registered role action {} for {}", spec.id(), spec.role());
        return spec;
    }

    @Override
    public @Nullable RoleActionSpec spec(ResourceLocation id) {
        return id == null ? null : specs.get(id);
    }

    @Override
    public Collection<RoleActionSpec> specs() {
        return Collections.unmodifiableCollection(specs.values());
    }

    @Override
    public List<RoleActionSpec> specsFor(RoleKey role) {
        if (role == null) {
            return List.of();
        }
        List<RoleActionSpec> out = new ArrayList<>();
        for (RoleActionSpec spec : specs.values()) {
            if (role.equals(spec.role())) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public RoleActionResult dispatch(ResourceLocation actionId, @Nullable UUID playerId,
                                     @Nullable RoleKey currentRole, byte[] payload, int sequence) {
        return run(actionId, playerId, currentRole, payload, sequence, null, false);
    }

    @Override
    public RoleActionResult receiveC2S(@Nullable ServerPlayer player, ResourceLocation actionId,
                                       byte[] payload, int sequence) {
        if (player == null) {
            return RoleActionResult.reject(RoleActionResult.UNKNOWN);
        }
        // Handshake gate (audit P1-4): refuse role actions for clients that have
        // not passed the §14.2 handshake (missing provider / API mismatch /
        // definition-hash mismatch). The reason is echoed to the client.
        try {
            String handshakeReason = handshakeGate.apply(player);
            if (handshakeReason != null) {
                RoleActionResult blocked = RoleActionResult.reject(
                        RoleActionResult.HANDSHAKE, handshakeReason);
                echoResult(player, actionId, sequence, blocked);
                return blocked;
            }
        } catch (Throwable t) {
            LOGGER.warn("handshake gate check failed for {}; blocking action", player.getUUID(), t);
            RoleActionResult blocked = RoleActionResult.reject(RoleActionResult.HANDSHAKE,
                    "握手门控检查失败，禁止角色动作");
            echoResult(player, actionId, sequence, blocked);
            return blocked;
        }
        UUID playerId = player.getUUID();
        RoleKey current = currentRoleLookup.apply(player);
        boolean alive = Boolean.TRUE.equals(aliveLookup.apply(player));
        RoleActionResult result = run(actionId, playerId, current, payload, sequence, player, alive);
        echoResult(player, actionId, sequence, result);
        return result;
    }

    @Override
    public void sendTo(@Nullable ServerPlayer player, ResourceLocation actionId, byte[] payload) {
        RoleActionSpec spec = spec(actionId);
        if (player == null || spec == null) {
            return;
        }
        if (spec.direction() == RoleActionDirection.C2S) {
            return;
        }
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > spec.maxBytes()) {
            LOGGER.debug("Dropping oversized S2C action {} ({} > {})", actionId, body.length, spec.maxBytes());
            return;
        }
        s2cSender.send(player, actionId, body);
    }

    @Override
    public synchronized void freeze() {
        this.frozen = true;
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    /** Snapshot/restore seam for provider-scoped registration transactions. */
    public synchronized RegistrationSnapshot snapshotForTransaction() {
        return new RegistrationSnapshot(new LinkedHashMap<>(specs), frozen);
    }

    /** Restores action declarations only; live player rate/cooldown state is untouched. */
    public synchronized void restoreTransactionSnapshot(RegistrationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        specs.clear();
        specs.putAll(snapshot.specs());
        frozen = snapshot.frozen();
    }

    /**
     * Test isolation: drop rate/cooldown/sequence windows. Pass {@code true} to
     * also forget registered specs.
     */
    public synchronized void clear(boolean includingSpecs) {
        rateWindows.clear();
        lastUseMs.clear();
        seqWindows.clear();
        if (includingSpecs) {
            specs.clear();
            frozen = false;
        }
    }

    public List<String> describe(@Nullable RoleKey role) {
        List<String> lines = new ArrayList<>();
        for (RoleActionSpec spec : specs.values()) {
            if (role != null && !role.equals(spec.role())) {
                continue;
            }
            lines.add(spec.id()
                    + " role=" + spec.role()
                    + " dir=" + spec.direction()
                    + " max=" + spec.maxBytes()
                    + " rate=" + spec.ratePerSecond() + "/s"
                    + " cd=" + spec.cooldownTicks() + "t"
                    + " target=" + spec.targetDecoder());
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // §12.4 validation chain
    // ------------------------------------------------------------------

    /**
     * Runs the fixed validation order. {@code player} may be {@code null} (pure
     * {@link #dispatch} test path); {@code aliveKnown} only applies to a live
     * player.
     */
    private RoleActionResult run(ResourceLocation actionId, @Nullable UUID playerId,
                                 @Nullable RoleKey currentRole, byte[] payload, int sequence,
                                 @Nullable ServerPlayer player, boolean aliveKnown) {
        // 1. action exists
        RoleActionSpec spec = spec(actionId);
        if (spec == null) {
            return RoleActionResult.reject(RoleActionResult.UNKNOWN);
        }
        // 2. direction
        if (spec.direction() == RoleActionDirection.S2C) {
            return RoleActionResult.reject(RoleActionResult.WRONG_DIRECTION);
        }
        byte[] body = payload == null ? new byte[0] : payload;
        // 4. spec max bytes (3 = hard decode cap already applied at packet decode)
        if (body.length > spec.maxBytes()) {
            return RoleActionResult.reject(RoleActionResult.TOO_LARGE);
        }
        // 5. sequence / replay / stale (integer wraparound handled by signed int delta)
        GateKey gate = new GateKey(spec.id(), playerId);
        if (playerId != null) {
            SeqVerdict verdict = checkSequence(gate, sequence);
            if (verdict == SeqVerdict.REPLAY) {
                return RoleActionResult.reject(RoleActionResult.REPLAY);
            }
            if (verdict == SeqVerdict.STALE) {
                return RoleActionResult.reject(RoleActionResult.STALE);
            }
        }
        // 6. current role must match the bound role
        if (spec.requireCurrentRole() && (currentRole == null || !currentRole.equals(spec.role()))) {
            return RoleActionResult.reject(RoleActionResult.WRONG_ROLE);
        }
        // 7. alive / spectator
        if (spec.requireAlive() && player != null && !aliveKnown) {
            return RoleActionResult.reject(RoleActionResult.DEAD);
        }
        // 8. structured target: decode + online + same world (decoder-driven)
        UUID targetId = null;
        if (spec.targetDecoder() == ActionTargetCodec.PLAYER_UUID) {
            if (player == null) {
                // Pure dispatch path without a live player: decode for the context only.
                targetId = decodePlayerUuid(body);
            } else {
                UUID decoded = decodePlayerUuid(body);
                if (decoded == null) {
                    return RoleActionResult.reject(RoleActionResult.TARGET, "target uuid required");
                }
                ServerPlayer target = player.getServer() == null
                        ? null
                        : player.getServer().getPlayerList().getPlayer(decoded);
                if (target == null) {
                    return RoleActionResult.reject(RoleActionResult.TARGET, "target offline");
                }
                if (player.level() != target.level()) {
                    return RoleActionResult.reject(RoleActionResult.TARGET, "target in another world");
                }
                targetId = decoded;
                // 8b. target alive (only for PLAYER_UUID targets when required)
                if (spec.requireTargetAlive() && !Boolean.TRUE.equals(aliveLookup.apply(target))) {
                    return RoleActionResult.reject(RoleActionResult.TARGET, "target dead");
                }
                // 9. distance / line of sight (only for PLAYER_UUID targets)
                if (spec.maxDistance() > 0 && player.distanceTo(target) > spec.maxDistance()) {
                    return RoleActionResult.reject(RoleActionResult.RANGE);
                }
                if (spec.requireLineOfSight() && !player.hasLineOfSight(target)) {
                    return RoleActionResult.reject(RoleActionResult.LINE_OF_SIGHT);
                }
            }
        }
        long now = clock.now();
        // 10. cooldown
        if (spec.cooldownTicks() > 0) {
            Long last = lastUseMs.get(gate);
            long cooldownMs = spec.cooldownTicks() * 50L;
            if (last != null && now - last < cooldownMs) {
                return RoleActionResult.reject(RoleActionResult.COOLDOWN);
            }
        }
        // 11. rate window
        if (!allowRate(gate, spec.ratePerSecond(), now)) {
            return RoleActionResult.reject(RoleActionResult.RATE);
        }
        // 12. handler
        RoleActionHandler handler = spec.handler();
        if (handler == null) {
            return RoleActionResult.reject(RoleActionResult.HANDLER);
        }
        // Count attempts before calling provider code. Otherwise an action
        // which always rejects (or throws) can be used to bypass rate limiting.
        recordRate(gate, now);
        try {
            RoleActionResult result = handler.handle(new RoleActionContext(
                    spec.role(), playerId, body, sequence, targetId));
            if (result != null && result.ok()) {
                lastUseMs.put(gate, now);
                if (playerId != null) {
                    recordSequence(gate, sequence);
                }
            }
            return result == null ? RoleActionResult.reject(RoleActionResult.HANDLER) : result;
        } catch (Throwable t) {
            LOGGER.warn("Role action {} threw", spec.id(), t);
            return RoleActionResult.reject(RoleActionResult.HANDLER, t.getClass().getSimpleName());
        }
    }

    /** Big-endian 16-byte player UUID, or {@code null} when the payload is too short. */
    private static @Nullable UUID decodePlayerUuid(byte[] body) {
        if (body == null || body.length < 16) {
            return null;
        }
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (body[i] & 0xFFL);
            lsb = (lsb << 8) | (body[8 + i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    private void echoResult(@Nullable ServerPlayer player, ResourceLocation actionId,
                            int sequence, RoleActionResult result) {
        if (player == null) {
            return;
        }
        try {
            resultSender.send(player, actionId, sequence, result.ok(), result.reasonKey(), result.payload());
        } catch (Throwable t) {
            LOGGER.warn("Role action {} result echo failed", actionId, t);
        }
    }

    private enum SeqVerdict { NEW, REPLAY, STALE }

    private SeqVerdict checkSequence(GateKey gate, int seq) {
        Deque<Integer> window = seqWindows.computeIfAbsent(gate, k -> new ArrayDeque<>());
        synchronized (window) {
            if (window.contains(seq)) {
                return SeqVerdict.REPLAY;
            }
            if (!window.isEmpty()) {
                int max = Integer.MIN_VALUE;
                for (int s : window) {
                    if (s > max) {
                        max = s;
                    }
                }
                // Signed int subtraction wraps correctly for real gaps < 2^31, so an
                // Integer.MAX_VALUE → Integer.MIN_VALUE wraparound still reads as +1.
                int delta = seq - max;
                if (delta < -STALE_BEHIND) {
                    return SeqVerdict.STALE;
                }
            }
            return SeqVerdict.NEW;
        }
    }

    private void recordSequence(GateKey gate, int seq) {
        Deque<Integer> window = seqWindows.computeIfAbsent(gate, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(seq);
            while (window.size() > REPLAY_WINDOW) {
                window.pollFirst();
            }
        }
    }

    private boolean allowRate(GateKey gate, int perSecond, long now) {
        Deque<Long> window = rateWindows.computeIfAbsent(gate, k -> new ArrayDeque<>());
        synchronized (window) {
            long cutoff = now - 1000L;
            while (!window.isEmpty() && window.peekFirst() <= cutoff) {
                window.pollFirst();
            }
            return window.size() < perSecond;
        }
    }

    private void recordRate(GateKey gate, long now) {
        Deque<Long> window = rateWindows.computeIfAbsent(gate, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(now);
        }
    }

    private static boolean lookupAlive(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator();
    }

    private static @Nullable RoleKey lookupCurrentRole(ServerPlayer player) {
        if (player == null || player.level() == null) {
            return null;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            if (game == null) {
                return null;
            }
            SRERole role = game.getRole(player);
            if (role == null || role.identifier() == null) {
                return null;
            }
            return RoleKey.of(role.identifier());
        } catch (Throwable t) {
            return null;
        }
    }

    /** S2C push channel (server-initiated). */
    @FunctionalInterface
    public interface S2CSender {
        void send(ServerPlayer player, ResourceLocation actionId, byte[] payload);
    }

    /** C2S response channel; echoes the request sequence (fix-doc §12.2/§12.5). */
    @FunctionalInterface
    public interface ResultSender {
        void send(ServerPlayer player, ResourceLocation actionId, int sequence,
                  boolean ok, String reasonKey, byte[] payload);
    }

    @FunctionalInterface
    public interface Clock {
        long now();
    }

    private record GateKey(ResourceLocation actionId, @Nullable UUID playerId) {}

    /** Public rollback token used only during the startup registration phase. */
    public record RegistrationSnapshot(Map<ResourceLocation, RoleActionSpec> specs, boolean frozen) {}
}