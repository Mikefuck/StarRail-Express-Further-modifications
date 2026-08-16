package com.habitrain.core.api.role.v2.client;

/**
 * Skin slot on a role (design §5.11 / P2).
 *
 * <p>{@link #NORMAL} maps to {@code SRERole.getNormalSkin}; {@link #PSYCHO}
 * to {@code getPsychoSkin}. {@link #DYNAMIC} is resolved by a provider
 * callback on the client when the two static slots are not enough.
 */
public enum RoleSkinKind {
    NORMAL,
    PSYCHO,
    DYNAMIC
}
