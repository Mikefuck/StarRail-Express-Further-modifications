package com.habitrain.core.api.role.v2.action;

import net.minecraft.resources.ResourceLocation;

/**
 * Callback receiving the authoritative result of a client-sent role action
 * (fix-doc §12.5). Invoked once on the client thread when the server echoes
 * the request, or on timeout / disconnect with a synthetic
 * {@link RoleActionResult#TIMEOUT}/{@link RoleActionResult#DISCONNECTED}.
 */
@FunctionalInterface
public interface RoleActionResultCallback {

    void onResult(ResourceLocation actionId, RoleActionResult result);
}
