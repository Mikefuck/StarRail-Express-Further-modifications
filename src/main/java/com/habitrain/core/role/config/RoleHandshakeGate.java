package com.habitrain.core.role.config;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative handshake gate (audit P1-4).
 *
 * <p>The client reports its local manifest (see
 * {@link com.habitrain.core.network.RoleHandshakeReportPayload}) after
 * receiving the server manifest; the gate computes the §14.2 result on the
 * server side and answers whether a player may execute role actions:
 *
 * <ul>
 *   <li>{@code OK} — actions allowed.</li>
 *   <li>{@code DEGRADED_CLIENT_EXTENSION} — actions allowed (presentation-only
 *       mismatch degrades to read-only client extensions).</li>
 *   <li>{@code HASH_MISMATCH} — actions blocked: the client's gameplay-definition
 *       fingerprint differs, so it would act on stale role data.</li>
 *   <li>{@code REJECTED_MISSING_PROVIDER} — actions blocked: a required provider
 *       or a compatible API version is missing on the client.</li>
 *   <li>No report at all — actions blocked: the client never completed the
 *       handshake (e.g. it lacks the mod, so it cannot have validated the
 *       definition hash).</li>
 * </ul>
 *
 * <p>The gate is per-player and cleared on disconnect. It is deliberately
 * strict (fail closed) so a stale or missing report can never enable gameplay
 * that depends on the client knowing the current role definitions.
 */
public final class RoleHandshakeGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleHandshakeGate");

    public static final RoleHandshakeGate INSTANCE = new RoleHandshakeGate();

    private final Map<UUID, ClientManifest> reports = new ConcurrentHashMap<>();
    /** Server-manifest source; injectable so unit tests avoid FabricLoader. */
    private volatile java.util.function.Supplier<RoleManifest> manifestSupplier = RoleManifestService::build;

    private RoleHandshakeGate() {}

    /** Test seam: replaces the server-manifest source (production = {@link RoleManifestService#build}). */
    public void setManifestSupplier(java.util.function.Supplier<RoleManifest> supplier) {
        this.manifestSupplier = supplier == null ? RoleManifestService::build : supplier;
    }

    /** Records the client's reported local manifest (C2S receiver). */
    public void record(UUID playerId, ClientManifest local) {
        if (playerId == null) {
            return;
        }
        if (local == null) {
            reports.remove(playerId);
            return;
        }
        reports.put(playerId, local);
        LOGGER.debug("Handshake report recorded for {}", playerId);
    }

    /** Removes a player's report on disconnect. */
    public void clear(UUID playerId) {
        if (playerId != null) {
            reports.remove(playerId);
        }
    }

    /** The last reported client manifest for a player, or {@code null}. */
    public @Nullable ClientManifest reportOf(UUID playerId) {
        return playerId == null ? null : reports.get(playerId);
    }

    /**
     * The authoritative handshake result for a player. A missing report fails
     * closed as {@code REJECTED_MISSING_PROVIDER}.
     */
    public RoleHandshakeResult resultFor(UUID playerId) {
        ClientManifest local = reportOf(playerId);
        if (local == null) {
            return RoleHandshakeResult.rejected(java.util.List.of(),
                    "客户端未完成角色扩展握手（未上报本地 manifest）。若已安装本模组，请重新加入；"
                            + "否则缺少必需的角色扩展 provider，无法执行角色动作。");
        }
        try {
            return RoleHandshakeMatcher.match(manifestSupplier.get(), local);
        } catch (Throwable t) {
            LOGGER.warn("Handshake match failed for {}; failing closed", playerId, t);
            return RoleHandshakeResult.hashMismatch("握手计算失败，禁止角色动作");
        }
    }

    /** Whether the player may execute role actions under the current handshake. */
    public boolean isActionAllowed(UUID playerId) {
        RoleHandshakeStatus status = resultFor(playerId).status();
        return status == RoleHandshakeStatus.OK
                || status == RoleHandshakeStatus.DEGRADED_CLIENT_EXTENSION;
    }

    /** Human-readable reason when actions are blocked, or {@code null} when allowed. */
    public @Nullable String blockReason(UUID playerId) {
        if (isActionAllowed(playerId)) {
            return null;
        }
        return resultFor(playerId).message();
    }
}