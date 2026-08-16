package com.habitrain.core.api.role.v2.capability;

/**
 * Runtime availability of one capability adapter.
 *
 * <p>{@link #UNAVAILABLE} is the dedicated-server / missing-mod answer:
 * policies stay registered, but no external class is loaded and evaluators
 * still return a safe decision (usually "do not mute").
 */
public enum RoleCapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED
}
