package com.habitrain.core.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S1RoleWireBoundaryTest {
    @Test void truncatedActionIsRejectedBeforeBodyAllocation() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeResourceLocation(ResourceLocation.fromNamespaceAndPath("test", "action"));
            buf.writeVarInt(1); buf.writeVarInt(65536);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> RoleActionC2SPayload.CODEC.decode(buf));
        } finally { buf.release(); }
    }
    @Test void roleConfigByteBudgetIsSymmetricForMultibyteText() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(io.netty.handler.codec.EncoderException.class, () -> RoleConfigUpdatePayload.CODEC.encode(buf,
                    new RoleConfigUpdatePayload("中".repeat(65_537))));
        } finally { buf.release(); }
        var good = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var payload = new RoleConfigUpdatePayload("中".repeat(65_536));
            RoleConfigUpdatePayload.CODEC.encode(good, payload);
            assertEquals(payload, RoleConfigUpdatePayload.CODEC.decode(good));
        } finally { good.release(); }
    }
}
