package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the real dependency's death order, which is why inventory-only loot misses weapons. */
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

    private static ClassNode read(String name) throws Exception {
        try (var stream = EnvyDeathOrderTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
