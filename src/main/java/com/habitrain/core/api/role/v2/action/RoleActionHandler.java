package com.habitrain.core.api.role.v2.action;

/**
 * Server-thread handler for one managed role action.
 *
 * <p>Must be side-effect free on the calling thread until the platform has
 * already validated role, size, rate and cooldown. Throwables are isolated
 * and surfaced as {@link RoleActionResult#HANDLER}.
 */
@FunctionalInterface
public interface RoleActionHandler {
    RoleActionResult handle(RoleActionContext ctx);
}
