package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.game.sre.mixin.LustDeathPenaltyMixin;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

class LustRestrictionSyncTest {
    private static final class Fixture extends LustDeathPenaltyMixin {
        private int syncs;
        @Override public void sync() { syncs++; }
    }

    @Test void unchangedReconciliationSendsNoRepeatedPackets() {
        var server = new Fixture();
        server.habitrain$setLustIdentityHidden(true);
        for (int i = 0; i < 300; i++) server.habitrain$setLustIdentityHidden(true);
        assertEquals(1, server.syncs);
        server.habitrain$setLustIdentityHidden(false);
        assertEquals(2, server.syncs);
    }

    @Test void clearingPacketReplacesClientHiddenStateAndLeavesUpstreamFieldsIntact() throws Exception {
        var server = new Fixture();
        var client = new Fixture();
        CompoundTag packet = new CompoundTag();
        packet.putLong("penaltyExpiry", 900);
        packet.putBoolean("chatEnabled", false);
        CompoundTag before = packet.copy();
        server.habitrain$setLustIdentityHidden(true);
        write(server, packet);
        read(client, packet);
        assertTrue(combined(client, false));
        server.habitrain$setLustIdentityHidden(false);
        write(server, packet);
        read(client, packet);
        assertFalse(combined(client, false));
        assertTrue(combined(client, true), "Conspirator/timer/camera penalties still control the same visibility gate");
        packet.remove(LustIdentityRestriction.SYNC_KEY);
        assertEquals(before, packet);
    }

    @Test void missingFlagAlsoClearsPreviousClientState() throws Exception {
        var client = new Fixture();
        client.habitrain$setLustIdentityHidden(true);
        read(client, new CompoundTag());
        assertFalse(combined(client, false));
    }

    private static boolean combined(Fixture fixture, boolean upstream) throws Exception {
        var method = LustDeathPenaltyMixin.class.getDeclaredMethod("habitrain$includeLust", CallbackInfoReturnable.class);
        method.setAccessible(true);
        var callback = new CallbackInfoReturnable<Boolean>("hasPenalty", true, upstream);
        method.invoke(fixture, callback);
        return callback.getReturnValue();
    }

    private static void write(Fixture fixture, CompoundTag tag) throws Exception { transfer(fixture, tag, "habitrain$writeLust"); }
    private static void read(Fixture fixture, CompoundTag tag) throws Exception { transfer(fixture, tag, "habitrain$readLust"); }

    private static void transfer(Fixture fixture, CompoundTag tag, String name) throws Exception {
        var method = LustDeathPenaltyMixin.class.getDeclaredMethod(name, CompoundTag.class, HolderLookup.Provider.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(fixture, tag, null, new CallbackInfo(name, false));
    }
}
