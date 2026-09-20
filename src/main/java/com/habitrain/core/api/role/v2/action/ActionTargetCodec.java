package com.habitrain.core.api.role.v2.action;

/**
 * Structured target scheme for a managed role action (fix-doc §12.3).
 *
 * <p>The payload's first bytes are only meaningful when a decoder is declared.
 * The platform decodes and validates the target into
 * the {@link RoleActionContext} so the handler never re-parses untrusted bytes.
 *
 * <p><b>审核 R-02</b>：旧 javadoc 声称 distance / line-of-sight / target-alive
 * 检查「保留给 {@link #PLAYER_UUID}」，与实现相反——{@code maxDistance} 对
 * {@link #PLAYER_UUID} 与 {@link #BLOCK_POS} 都生效；
 * {@code requireLineOfSight} / {@code requireTargetAlive} 才只对
 * {@link #PLAYER_UUID} 生效。
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
