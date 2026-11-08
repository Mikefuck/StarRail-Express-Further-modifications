package com.habitrain.core.api.role.v2.action;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Outcome of a managed role action. {@link #reasonKey()} is a translation key
 * the client can show; {@link #detail()} is an optional unlocalized extra.
 */
public record RoleActionResult(boolean ok, String reasonKey, @Nullable String detail, byte[] payload) {

    public static final String OK = "roleapi.action.ok";
    public static final String UNKNOWN = "roleapi.action.unknown";
    public static final String WRONG_ROLE = "roleapi.action.wrong_role";
    public static final String WRONG_DIRECTION = "roleapi.action.wrong_direction";
    public static final String TOO_LARGE = "roleapi.action.too_large";
    public static final String RATE = "roleapi.action.rate";
    public static final String COOLDOWN = "roleapi.action.cooldown";
    public static final String DEAD = "roleapi.action.dead";
    public static final String HANDLER = "roleapi.action.handler";
    public static final String RANGE = "roleapi.action.range";
    public static final String LINE_OF_SIGHT = "roleapi.action.los";
    public static final String REPLAY = "roleapi.action.replay";
    public static final String STALE = "roleapi.action.stale";
    public static final String TARGET = "roleapi.action.target";
    public static final String TIMEOUT = "roleapi.action.timeout";
    public static final String DISCONNECTED = "roleapi.action.disconnected";
    /** Handshake gate refused the action (audit P1-4): missing provider / API mismatch / hash mismatch. */
    public static final String HANDSHAKE = "roleapi.action.handshake";
    /** Provider/entry config gate refused the action (audit P1-2). */
    public static final String CONFIG_DISABLED = "roleapi.action.config_disabled";

    public RoleActionResult {
        Objects.requireNonNull(reasonKey, "reasonKey");
        payload = payload == null || payload.length == 0 ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.length == 0 ? payload : payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RoleActionResult other)) return false;
        return ok == other.ok
                && Objects.equals(reasonKey, other.reasonKey)
                && Objects.equals(detail, other.detail)
                && java.util.Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(ok, reasonKey, detail);
        return 31 * result + java.util.Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "RoleActionResult[ok=" + ok
                + ", reasonKey=" + reasonKey
                + ", detail=" + detail
                + ", payload=" + java.util.Arrays.toString(payload) + "]";
    }

    public RoleActionResult(boolean ok, String reasonKey, @Nullable String detail) {
        this(ok, reasonKey, detail, new byte[0]);
    }

    public static RoleActionResult success() {
        return new RoleActionResult(true, OK, null, new byte[0]);
    }

    public static RoleActionResult success(byte[] payload) {
        return new RoleActionResult(true, OK, null, payload);
    }

    public static RoleActionResult reject(String reasonKey) {
        return new RoleActionResult(false, reasonKey, null, new byte[0]);
    }

    public static RoleActionResult reject(String reasonKey, @Nullable String detail) {
        return new RoleActionResult(false, reasonKey, detail, new byte[0]);
    }

    /** Typed built-in rejection; the string factories continue to support custom addon keys. */
    public static RoleActionResult rejectReason(RoleActionReason reason, @Nullable String detail) {
        if (Objects.requireNonNull(reason, "reason") == RoleActionReason.OK)
            throw new IllegalArgumentException("OK is not a rejection reason");
        return reject(reason.translationKey(), detail);
    }

    public java.util.Optional<RoleActionReason> knownReason() {
        return RoleActionReason.fromKey(reasonKey);
    }
}
