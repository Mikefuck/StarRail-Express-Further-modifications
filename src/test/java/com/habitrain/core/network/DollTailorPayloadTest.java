package com.habitrain.core.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DollTailorPayloadTest {
    @Test void selectionAndRosterRoundTripWithIndependentBlueprints() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(new DollTailorActionPayload(null), roundTrip(DollTailorActionPayload.CODEC, new DollTailorActionPayload(null)));
        assertEquals(new DollTailorActionPayload(first), roundTrip(DollTailorActionPayload.CODEC, new DollTailorActionPayload(first)));
        var state = new DollTailorStatePayload(first, 101, List.of(
                new DollTailorStatePayload.Entry(first, "玩家甲", 300), new DollTailorStatePayload.Entry(second, "Mike", 63)));
        assertEquals(state, roundTrip(DollTailorStatePayload.CODEC, state));
    }

    @Test void protectionLinksAndImmediateRemovalRoundTrip() {
        var link = new DollProtectionPayload.Link(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new Vec3(1.25, 64, -28));
        var active = new DollProtectionPayload(List.of(link), false);
        assertEquals(active, roundTrip(DollProtectionPayload.CODEC, active));
        var broken = new DollProtectionPayload(List.of(), true);
        assertEquals(broken, roundTrip(DollProtectionPayload.CODEC, broken));
    }

    @Test void oversizedAndNegativeListsAreRejectedBeforeAllocation() {
        for (int count : new int[]{-1, 257, Integer.MAX_VALUE}) {
            var roster = new FriendlyByteBuf(Unpooled.buffer());
            try {
                roster.writeBoolean(false); roster.writeVarInt(0); roster.writeVarInt(count);
                assertThrows(DecoderException.class, () -> DollTailorStatePayload.CODEC.decode(roster));
            } finally { roster.release(); }
            var links = new FriendlyByteBuf(Unpooled.buffer());
            try {
                links.writeBoolean(false); links.writeVarInt(count);
                assertThrows(DecoderException.class, () -> DollProtectionPayload.CODEC.decode(links));
            } finally { links.release(); }
        }
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { codec.encode(buffer, value); T result = codec.decode(buffer); assertFalse(buffer.isReadable()); return result; }
        finally { buffer.release(); }
    }
}
