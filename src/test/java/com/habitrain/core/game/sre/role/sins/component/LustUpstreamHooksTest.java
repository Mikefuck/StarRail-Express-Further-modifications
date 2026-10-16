package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Check the actual bundled upstream bytecode used by the new mixins, without initializing Minecraft. */
class LustUpstreamHooksTest {
    private static ClassNode read(String type) throws IOException {
        try (var input = LustUpstreamHooksTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(input, type);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test void settlementHasOnePlayerIterationToPlaceFollowersLast() throws IOException {
        var node = read("io/wifi/starrailexpress/game/GameUtils");
        long matches = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("recordWinStats")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/server/level/ServerLevel")
                        && call.name.equals("players") && call.desc.equals("()Ljava/util/List;")) matches++;
            }
        }
        assertEquals(1, matches, "Upstream settlement iteration changed; update LustFollowVictoryMixin");
    }

    @Test void deathPenaltyStillChecksRolesAndDeathEventOverloadExists() throws IOException {
        var node = read("org/agmas/noellesroles/component/DeathPenaltyComponent");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("check")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("io/wifi/starrailexpress/cca/SREGameWorldComponent")
                        && call.name.equals("isRole")
                        && call.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lio/wifi/starrailexpress/api/SRERole;)Z")) found = true;
            }
        }
        assertTrue(found, "Death penalty role check changed");
        var events = read("org/agmas/noellesroles/init/events/NRDeathEvents");
        assertTrue(events.methods.stream().anyMatch(m -> m.name.equals("handleDeathPenalty")
                && m.desc.equals("(Lnet/minecraft/world/level/Level;Ljava/util/List;ZZ)V")));
    }
}
