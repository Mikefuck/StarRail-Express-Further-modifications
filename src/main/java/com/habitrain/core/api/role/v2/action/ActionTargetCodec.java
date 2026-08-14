package com.habitrain.core.api.role.v2.action;

/**
 * Structured target scheme for a managed role action (fix-doc §12.3).
 *
 * <p>The payload's first bytes are only meaningful when a decoder is declared.
 * Distance / line-of-sight checks are reserved to actions whose decoder is
 * {@link #PLAYER_UUID} — the platform decodes a verified target into the
 * {@link RoleActionContext} so the handler never re-parses untrusted bytes.
 */
public enum ActionTargetCodec {

    /** No structured target; the payload is opaque to the platform. */
    NONE,

    /** First 16 payload bytes are a big-endian player UUID. */
    PLAYER_UUID
}
