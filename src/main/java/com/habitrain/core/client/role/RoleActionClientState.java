package com.habitrain.core.client.role;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Last multiplexed role-action S2C the local client received.
 *
 * <p>Providers that need more than the last packet can poll this or wait
 * for a later client-extension hook. Dedicated servers never load this class.
 */
@Environment(EnvType.CLIENT)
public final class RoleActionClientState {

    private static volatile @Nullable ResourceLocation lastId;
    private static volatile int lastSequence;
    private static volatile boolean lastOk;
    private static volatile String lastReason = "";
    private static volatile byte[] lastPayload = new byte[0];

    private RoleActionClientState() {}

    public static void accept(ResourceLocation id, int sequence, boolean ok, String reason, byte[] payload) {
        lastId = id;
        lastSequence = sequence;
        lastOk = ok;
        lastReason = reason == null ? "" : reason;
        lastPayload = payload == null ? new byte[0] : payload;
    }

    public static @Nullable ResourceLocation lastId() { return lastId; }
    public static int lastSequence() { return lastSequence; }
    public static boolean lastOk() { return lastOk; }
    public static String lastReason() { return lastReason; }
    public static byte[] lastPayload() { return lastPayload; }

    public static void clear() {
        lastId = null;
        lastSequence = 0;
        lastOk = false;
        lastReason = "";
        lastPayload = new byte[0];
    }
}
