package com.habitrain.core.api.role.v2.action;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Client-side role-action result API (fix-doc §12.5).
 *
 * <p>Replaces the "last packet only" model with per-request callbacks: concurrent
 * requests are tracked by their prediction {@code sequence}, time out if the
 * server stays silent, and are completed on disconnect. Server-initiated pushes
 * are delivered separately through push listeners. {@code payload} handed to
 * callbacks is a defensive copy.
 *
 * <p>Kept free of {@code @Environment(EnvType.CLIENT)} on the interface declaration
 * so dedicated servers can class-load common references safely without
 * {@code NoClassDefFoundError} (ROLE-API-04); runtime calls on a dedicated server
 * fail fast in {@link #instance()}.
 */
public interface RoleActionClientApi {

    /**
     * The client-side session (bound to the running client).
     * Only {@code ClientModInitializer} / other client-side code may call this.
     */
    static RoleActionClientApi instance() {
        EnvType env = null;
        try {
            env = FabricLoader.getInstance().getEnvironmentType();
        } catch (RuntimeException ignored) {
            // JUnit / unloaded Fabric still resolves through the SPI bridge.
        }
        if (env != null && env != EnvType.CLIENT) {
            throw new IllegalStateException(
                    "RoleActionClientApi.instance() may only be called from ClientModInitializer or other client-side code");
        }
        // 审核 A2 / X-CUT-11：通过 RoleSpi 获取客户端会话，并保留底层异常作为 cause。
        final Object session;
        try {
            com.habitrain.core.api.spi.RoleSpi.RoleActionClientBridge bridge =
                    com.habitrain.core.api.spi.RoleSpi.actionClientOrNull();
            session = bridge != null ? bridge.session() : null;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to resolve RoleActionClientApi client session", e);
        }
        if (session instanceof RoleActionClientApi api) {
            return api;
        }
        throw new IllegalStateException("RoleActionClientApi client session is unavailable");
    }

    /**
     * Sends a C2S action with a callback. The callback fires when the server
     * echoes this request's sequence, or with {@link RoleActionResult#TIMEOUT}/
     * {@link RoleActionResult#DISCONNECTED} if it never does. {@code payload}
     * is copied defensively.
     */
    void send(ResourceLocation actionId, byte[] payload, @Nullable RoleActionResultCallback callback);

    /**
     * Routes a server response (not a push) to the pending request for
     * {@code (actionId, sequence)}. Invoked by the network receiver.
     */
    void onResult(ResourceLocation actionId, int sequence, boolean ok, String reasonKey, byte[] payload);

    /** Delivers a server-initiated push to the push listeners of {@code actionId}. */
    void onPush(ResourceLocation actionId, byte[] payload);

    /** Registers a listener for server-initiated pushes of {@code actionId}. */
    void addPushListener(ResourceLocation actionId, Consumer<byte[]> listener);

    /** Removes a previously registered push listener. */
    void removePushListener(ResourceLocation actionId, Consumer<byte[]> listener);

    /** Runs pending-request timeouts. Wired to the client tick by the entrypoint. */
    void tick();

    /** Completes every pending request (disconnect / world change) and drops state. */
    void clear();
}
