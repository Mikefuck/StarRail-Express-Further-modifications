package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.EnvyDeathLoot;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the real dependency's death order and EnvyDeathLootMixin's bytecode contract. */
class EnvyDeathOrderTest {
    @Test
    void confirmedKillFollowsSpectatorTransitionAndDeathDrops() throws Exception {
        ClassNode mode = read("io/wifi/starrailexpress/api/GameMode");
        var kill = mode.methods.stream().filter(method -> method.name.equals("killPlayer")).findFirst().orElseThrow();
        int spectator = -1, death = -1, reward = -1;
        for (int i = 0; i < kill.instructions.size(); i++) {
            if (!(kill.instructions.get(i) instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("net/minecraft/server/level/ServerPlayer") && call.name.equals("setGameMode")) spectator = i;
            if (call.owner.equals("io/wifi/starrailexpress/event/OnPlayerDeath") && call.name.equals("onPlayerDeath")) death = i;
            if (call.owner.equals("io/wifi/starrailexpress/event/OnPlayerDeathWithKiller") && call.name.equals("onPlayerDeath")) reward = i;
        }
        assertTrue(spectator >= 0 && death > spectator && reward > death,
                "Envy must reward only confirmed kills, after upstream has already processed weapon drops");
        ClassNode sword = read("org/agmas/noellesroles/content/item/ScarletPerceptionSwordItem");
        assertTrue(sword.interfaces.contains(
                "io/wifi/starrailexpress/content/item/api/SREItemProperties$DropRevolverWhenDead"));
    }

    @Test
    void envyDeathLootMixinWrapsKillPlayerAndDelegatesToEnvyDeathLoot() throws Exception {
        assertNotNull(EnvyDeathLootMixin.class);
        assertNotNull(EnvyDeathLoot.class);
        ClassNode mixin = read("com/habitrain/core/game/sre/mixin/EnvyDeathLootMixin");
        ClassNode gameMode = read("io/wifi/starrailexpress/api/GameMode");
        var killPlayer = gameMode.methods.stream()
                .filter(m -> "killPlayer".equals(m.name))
                .findFirst()
                .orElseThrow();

        var handler = mixin.methods.stream()
                .filter(m -> "habitrain$preserveDeathLoot".equals(m.name))
                .findFirst()
                .orElseThrow();
        assertNotNull(handler.visibleAnnotations, "habitrain$preserveDeathLoot must have visible annotations");
        AnnotationNode wrapMethod = handler.visibleAnnotations.stream()
                .filter(a -> "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;".equals(a.desc))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) annotationValue(wrapMethod, "method");
        assertEquals(List.of("killPlayer"), targets);

        Type[] expectedParams = Type.getArgumentTypes(killPlayer.desc);
        Type[] actualParams = Type.getArgumentTypes(handler.desc);
        assertEquals(expectedParams.length + 1, actualParams.length);
        for (int i = 0; i < expectedParams.length; i++) {
            assertEquals(expectedParams[i], actualParams[i]);
        }
        assertEquals("com.llamalad7.mixinextras.injector.wrapoperation.Operation",
                actualParams[actualParams.length - 1].getClassName());

        boolean callsDuringKill = false;
        for (int i = 0; i < handler.instructions.size(); i++) {
            if (handler.instructions.get(i) instanceof MethodInsnNode call
                    && "com/habitrain/core/game/sre/role/sins/EnvyDeathLoot".equals(call.owner)
                    && "duringKill".equals(call.name)) {
                callsDuringKill = true;
                break;
            }
        }
        assertTrue(callsDuringKill, "EnvyDeathLootMixin must invoke EnvyDeathLoot.duringKill");
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = EnvyDeathOrderTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
