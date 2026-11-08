package com.habitrain.core.network;

import com.habitrain.core.role.config.ClientManifest;
import com.habitrain.core.role.config.RoleHandshakeMatcher;
import com.habitrain.core.role.config.RoleHandshakeStatus;
import com.habitrain.core.role.config.RoleManifest;
import com.habitrain.core.role.config.RoleProviderManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleManifestPayloadTest {

    @Test
    void sixtyFiveProvidersSurviveTheActualWireCodec() {
        var rows = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> new RoleManifestPayload.ProviderRow("provider_" + i, "1", true)).toList();
        var payload = new RoleManifestPayload("2.0", rows, List.of("state"), "hash", "lobby", null,
                "presentation", "", List.of("client_hud_visual"));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            RoleManifestPayload.CODEC.encode(buffer, payload);
            assertEquals(payload, RoleManifestPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test
    void tooManyProvidersAreRejectedRatherThanSilentlyDropped() {
        var rows = java.util.stream.IntStream.range(0, 4097)
                .mapToObj(i -> new RoleManifestPayload.ProviderRow("p" + i, "1", true)).toList();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RoleManifestPayload("2.0", rows, List.of(), "hash", "lobby", null, "pres", "", List.of()));
    }

    @Test
    void fromManifestOmitsConfigJsonAndKeepsHandshakeHashes() {
        RoleManifest manifest = new RoleManifest(
                "2.0",
                List.of(new RoleProviderManifest("habitrain_dlc", "1.2.0", true)),
                Set.of("add"),
                "hashA",
                "role-snapshot-v1",
                null,
                "presA",
                "{\"providers\":{}}");
        RoleManifestPayload payload = RoleManifestPayload.fromManifest(manifest);
        assertEquals("", payload.configJson());

        RoleManifest roundTrip = payload.toManifest();
        assertEquals("hashA", roundTrip.definitionHash());
        assertEquals("presA", roundTrip.presentationHash());
        assertEquals("", roundTrip.configJson());
        assertEquals("2.0", roundTrip.coreApiVersion());

        ClientManifest local = new ClientManifest("2.0", Map.of("habitrain_dlc", "1.2.0"), true,
                "hashA", null, Set.of("habitrain_dlc"));
        assertEquals(RoleHandshakeStatus.OK, RoleHandshakeMatcher.match(roundTrip, local).status());
    }
}
