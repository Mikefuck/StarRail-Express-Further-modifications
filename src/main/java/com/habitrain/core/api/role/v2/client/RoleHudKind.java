package com.habitrain.core.api.role.v2.client;

/**
 * Declarative HUD widget kind (design §16.3).
 *
 * <p>Rendering status (audit P1-5): the stock client renders every kind as a
 * translated string at the spec position. {@link #TEXT} and {@link #BADGE} are
 * the exact string; {@link #ICON}, {@link #PROGRESS}, {@link #COOLDOWN} and
 * {@link #CHARGE} use a small kind prefix so experimental declarations are
 * visible instead of silently dropped. Providers needing real textures/values
 * should use a custom {@code RoleHudWidget}.
 */
public enum RoleHudKind {
    /** Rendered as translated text. */
    TEXT,
    /** Experimental (client_hud_visual): declared, not yet rendered. */
    ICON,
    /** Experimental (client_hud_visual): declared, not yet rendered. */
    PROGRESS,
    /** Experimental (client_hud_visual): declared, not yet rendered. */
    COOLDOWN,
    /** Experimental (client_hud_visual): declared, not yet rendered. */
    CHARGE,
    /** Rendered as translated text (same stock path as {@link #TEXT}). */
    BADGE
}
