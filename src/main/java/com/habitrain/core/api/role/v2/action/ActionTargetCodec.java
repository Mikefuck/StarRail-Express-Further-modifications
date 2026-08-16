package com.habitrain.core.api.role.v2.action;

/**
 * Structured target scheme for a managed role action (fix-doc §12.3).
 *
 * <p>The payload's first bytes are only meaningful when a decoder is declared.
 * Distance / line-of-sight / target-alive checks are reserved to
 * {@link #PLAYER_UUID} — the platform decodes and validates the target into
 * the {@link RoleActionContext} so the handler never re-parses untrusted bytes.
 */
public enum ActionTargetCodec {

    /** No structured target; the payload is opaque to the platform. */
    NONE,

    /** First 16 payload bytes are a big-endian player UUID. */
    PLAYER_UUID,

    /** Next 12 payload bytes are big-endian block X/Y/Z. */
    BLOCK_POS,

    /** Next 4 payload bytes are a big-endian entity id in the acting world. */
    ENTITY_ID
}
