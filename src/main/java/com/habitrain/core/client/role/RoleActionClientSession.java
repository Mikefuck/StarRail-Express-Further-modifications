package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.action.RoleActionClientApi;
import com.habitrain.core.api.role.v2.action.RoleActionResult;
import com.habitrain.core.api.role.v2.action.RoleActionResultCallback;
import com.habitrain.core.network.RoleActionC2SPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Client-side session implementing {@link RoleActionClientApi} (fix-doc §12.5).
 *
 * <p>Requests are tracked by their prediction sequence. The server's response
 * (echoing that sequence) resolves the pending callback; server-initiated
 * pushes are delivered separately. Timeout and disconnect completion run on the
 * client thread. The network sender and clock are injectable so the concurrency
 * and timeout logic is unit-testable without a live client.
 */
@Environment(EnvType.CLIENT)
public final class RoleActionClientSession implements RoleActionClientApi {

    public static final RoleActionClientSession INSTANCE = new RoleActionClientSession();

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleActionClient");

    private final Map<PendingKey, Pending> pending = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, Set<Consumer<byte[]>>> pushListeners = new ConcurrentHashMap<>();
    private final AtomicInteger sequenceCounter = new AtomicInteger();

    private volatile Sender sender = RoleActionClientSession::sendViaFabric;
    private volatile Clock clock = System::currentTimeMillis;
    private volatile long timeoutMs = 10_000L;
    private volatile boolean tickRegistered;

    private RoleActionClientSession() {}

    /** Replaces the network sender (tests). */
    public void setSender(Sender sender) {
        this.sender = sender == null ? (a, s, p) -> { } : sender;
    }

    public void setClock(Clock clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = Math.max(0, timeoutMs);
    }

    /** Registers the tick handler once; safe to call multiple times. */
    public void registerTick() {
        if (tickRegistered) {
            return;
        }
        tickRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick());
    }

    @Override
    public void send(ResourceLocation actionId, byte[] payload, @Nullable RoleActionResultCallback callback) {
        if (actionId == null) {
            return;
        }
        int sequence = nextSequence();
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        PendingKey key = new PendingKey(actionId, sequence);
        long deadline = timeoutMs <= 0 ? -1 : clock.now() + timeoutMs;
        pending.put(key, new Pending(actionId, copy, callback, deadline));
        try {
            sender.send(actionId, sequence, copy);
        } catch (Throwable t) {
            pending.remove(key);
            LOGGER.warn("Role action {} send failed locally", actionId, t);
            if (callback != null) {
                callback.onResult(actionId, RoleActionResult.reject(RoleActionResult.DISCONNECTED));
            }
        }
    }

    @Override
    public void onResult(ResourceLocation actionId, int sequence, boolean ok, String reasonKey, byte[] payload) {
        PendingKey key = new PendingKey(actionId, sequence);
        Pending p = pending.remove(key);
        if (p == null) {
            return; // late, already timed out, or never sent from here
        }
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        if (p.callback() != null) {
            try {
                p.callback().onResult(actionId, new RoleActionResult(ok, reasonKey, null, copy));
            } catch (Throwable t) {
                LOGGER.warn("Role action {} callback threw", actionId, t);
            }
        }
    }

    @Override
    public void onPush(ResourceLocation actionId, byte[] payload) {
        byte[] copy = payload == null ? new byte[0] : payload.clone();
        Set<Consumer<byte[]>> listeners = pushListeners.get(actionId);
        if (listeners == null) {
            return;
        }
        for (Consumer<byte[]> listener : listeners) {
            try {
                listener.accept(copy);
            } catch (Throwable t) {
                LOGGER.warn("Role action {} push listener threw", actionId, t);
            }
        }
    }

    @Override
    public void addPushListener(ResourceLocation actionId, Consumer<byte[]> listener) {
        if (actionId == null || listener == null) {
            return;
        }
        pushListeners.computeIfAbsent(actionId, id -> new CopyOnWriteArraySet<>()).add(listener);
    }

    @Override
    public void removePushListener(ResourceLocation actionId, Consumer<byte[]> listener) {
        if (actionId == null) {
            return;
        }
        Set<Consumer<byte[]>> listeners = pushListeners.get(actionId);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                pushListeners.remove(actionId, listeners);
            }
        }
    }

    @Override
    public void tick() {
        long now = clock.now();
        for (PendingKey key : pending.keySet()) {
            Pending p = pending.get(key);
            if (p == null || p.deadlineMs() < 0 || now < p.deadlineMs()) {
                continue;
            }
            if (pending.remove(key, p) && p.callback() != null) {
                try {
                    p.callback().onResult(key.actionId(), RoleActionResult.reject(RoleActionResult.TIMEOUT));
                } catch (Throwable t) {
                    LOGGER.warn("Role action {} timeout callback threw", key.actionId(), t);
                }
            }
        }
    }

    @Override
    public void clear() {
        for (PendingKey key : pending.keySet()) {
            Pending p = pending.remove(key);
            if (p != null && p.callback() != null) {
                try {
                    p.callback().onResult(key.actionId(), RoleActionResult.reject(RoleActionResult.DISCONNECTED));
                } catch (Throwable t) {
                    LOGGER.warn("Role action {} disconnect callback threw", key.actionId(), t);
                }
            }
        }
        pending.clear();
        pushListeners.clear();
    }

    /** Number of in-flight requests (diagnostics/tests). */
    public int pendingCount() {
        return pending.size();
    }

    private int nextSequence() {
        // int arithmetic wraps naturally; the server tracks a window and accepts
        // wraparound as a positive signed delta (fix-doc §12.2).
        return sequenceCounter.getAndUpdate(v -> v + 1);
    }

    private static void sendViaFabric(ResourceLocation actionId, int sequence, byte[] payload) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new RoleActionC2SPayload(actionId, sequence, payload));
    }

    private record PendingKey(ResourceLocation actionId, int sequence) {
        private PendingKey {
            Objects.requireNonNull(actionId, "actionId");
        }
    }

    private record Pending(ResourceLocation actionId, byte[] payload,
                           @Nullable RoleActionResultCallback callback, long deadlineMs) {
    }

    /** Network seam (injectable for tests). */
    @FunctionalInterface
    public interface Sender {
        void send(ResourceLocation actionId, int sequence, byte[] payload);
    }

    @FunctionalInterface
    public interface Clock {
        long now();
    }
}
