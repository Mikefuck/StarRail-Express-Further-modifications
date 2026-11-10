package com.habitrain.core.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdiotEffectPayloadTest {
    @Test void stateAndAllLocalEffectsRoundTrip() {
        for (int event = 0; event <= IdiotEffectPayload.JACKPOT; event++) {
            var value = new IdiotEffectPayload(event, new Vec3(1.25, 68, -7), 200, 60);
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                IdiotEffectPayload.CODEC.encode(b, value);
                assertEquals(value, IdiotEffectPayload.CODEC.decode(b));
                assertFalse(b.isReadable());
            } finally { b.release(); }
        }
    }
    @Test void malformedEventsCoordinatesAndCountersAreRejected() {
        for (var value : new IdiotEffectPayload[]{
                new IdiotEffectPayload(5, Vec3.ZERO, 0, 0),
                new IdiotEffectPayload(-1, Vec3.ZERO, 0, 0),
                new IdiotEffectPayload(0, new Vec3(Double.NaN, 0, 0), 0, 0),
                new IdiotEffectPayload(0, new Vec3(0, Double.POSITIVE_INFINITY, 0), 0, 0),
                new IdiotEffectPayload(0, Vec3.ZERO, -1, 0),
                new IdiotEffectPayload(0, Vec3.ZERO, 201, 0),
                new IdiotEffectPayload(0, Vec3.ZERO, 0, -1),
                new IdiotEffectPayload(0, Vec3.ZERO, 0, 61)}) {
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                IdiotEffectPayload.CODEC.encode(b, value);
                assertThrows(DecoderException.class, () -> IdiotEffectPayload.CODEC.decode(b));
            } finally { b.release(); }
        }
    }
}
