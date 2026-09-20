package com.habitrain.core.game;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemovedModePackagingTest {
    @Test
    void everyConfiguredMixinHasACompiledClass() throws Exception {
        var metadata = json("/fabric.mod.json");
        for (var entry : metadata.getAsJsonArray("mixins")) {
            String config = entry.isJsonObject()
                    ? entry.getAsJsonObject().get("config").getAsString() : entry.getAsString();
            var mixins = json("/" + config);
            String prefix = "/" + mixins.get("package").getAsString().replace('.', '/') + "/";
            for (String side : new String[] {"mixins", "client", "server"}) {
                if (!mixins.has(side)) continue;
                for (var name : mixins.getAsJsonArray(side)) {
                    assertNotNull(getClass().getResource(prefix + name.getAsString().replace('.', '/') + ".class"),
                            config + ": " + name.getAsString());
                }
            }
        }
    }

    private com.google.gson.JsonObject json(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return com.google.gson.JsonParser.parseString(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void retiredModeAndPacketsAreAbsentButSharedEntryPointsRemain() throws Exception {
        String root = "/com/habitrain/core/";
        for (String retired : new String[] {
                "game/blackout/BlackoutMode", "game/blackout/sre/SREBlackoutGameMode",
                "network/BlackoutTimerPayload", "network/BlackoutVoteCastPayload",
                "client/gui/BlackoutHudOverlay", "client/gui/BlackoutTaskShopScreen"}) {
            assertNull(getClass().getResource(root + retired + ".class"), retired);
        }
        for (String retained : new String[] {
                "HabiTrainCore", "NetworkRegistrar", "C2SReceiverRegistrar",
                "client/VoteKeyHandler", "game/sre/ForcedReadyJoinGate",
                "internal/CoreSpiRegistrar", "api/spi/CoreSpi",
                "role/change/RoleChangeServiceImpl",
                "role/override/RoleOverrideWinHook", "game/sre/SreRoleAssignmentEffects"}) {
            try (var stream = getClass().getResourceAsStream(root + retained + ".class")) {
                assertNotNull(stream, retained);
                // JVM symbolic references retain internal class names in the constant pool.
                String pool = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(pool.contains("com/habitrain/core/game/blackout/"), retained);
                assertFalse(pool.contains("com/habitrain/core/network/Blackout"), retained);
            }
        }
    }
}
