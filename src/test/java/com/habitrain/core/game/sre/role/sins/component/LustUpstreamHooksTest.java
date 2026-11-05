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

    @Test void commandAdaptersStillTargetOnlyTheSuccessfulStopHandler() throws IOException {
        var command = read("io/wifi/starrailexpress/content/command/SetDeathPenaltyCommand");
        var stop = command.methods.stream().filter(m -> m.name.equals("lambda$register$3")).findFirst().orElseThrow();
        assertEquals("(Lcom/mojang/brigadier/context/CommandContext;)I", stop.desc);
        assertTrue((stop.access & Opcodes.ACC_STATIC) != 0);
        assertEquals(1, calls(stop, "org/agmas/noellesroles/component/DeathPenaltyComponent", "init", "()V"));
        assertEquals(1, calls(stop, "net/minecraft/commands/CommandSourceStack", "sendSuccess", "(Ljava/util/function/Supplier;Z)V"));
        assertTrue(calls(stop, "net/minecraft/commands/CommandSourceStack", "getPlayer", "()Lnet/minecraft/server/level/ServerPlayer;") > 0);
    }

    @Test void upstreamAutoCleanupGatesAreIsolatedFromTheLustFlag() throws IOException {
        var node = read("org/agmas/noellesroles/component/DeathPenaltyComponent");
        for (String name : java.util.List.of("check", "serverTick")) {
            var method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            assertEquals(1, calls(method, node.name, "hasPenalty", "()Z"), name);
        }
    }

    @Test void lustCodeNeverWritesAnUpstreamPenaltyTimerOrResetsOtherSources() throws IOException {
        for (String type : java.util.List.of("com/habitrain/core/game/sre/role/sins/component/LustRestrictionService",
                "com/habitrain/core/game/sre/mixin/LustDeathPenaltyMixin")) {
            for (var method : read(type).methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("org/agmas/noellesroles/component/DeathPenaltyComponent")) {
                        assertFalse(java.util.Set.of("init", "clear", "clearAll", "setPenalty", "setPenaltyWithCameraLimit", "setPenaltyWithPositionLimit").contains(call.name), type);
                    }
                    if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                        assertNotEquals("org/agmas/noellesroles/component/DeathPenaltyComponent", field.owner);
                    }
                }
            }
        }
    }

    private static long calls(MethodNode method, String owner, String name, String descriptor) {
        long count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }
}
