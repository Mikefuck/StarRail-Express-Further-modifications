package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the actual order that made the old Fabric callback-only patch unreachable. */
class WrathDoorAttackCompatibilityTest {
    @Test
    void adventureGuardIsWrappedBeforeFabricCanFireTheAttackCallback() throws Exception {
        var target = read("net/minecraft/client/multiplayer/MultiPlayerGameMode");
        var attack = target.methods.stream().filter(m -> m.name.equals("startDestroyBlock")).findFirst().orElseThrow();
        int restriction = -1;
        int creativeCheck = -1;
        MethodInsnNode guard = null;
        for (int i = 0; i < attack.instructions.size(); i++) {
            if (attack.instructions.get(i) instanceof MethodInsnNode call) {
                if (call.name.equals("blockActionRestricted")) { restriction = i; guard = call; }
                if (call.name.equals("isCreative") && creativeCheck == -1) creativeCheck = i;
            }
        }
        assertTrue(restriction >= 0 && restriction < creativeCheck,
                "Minecraft rejects adventure attacks before the Fabric callback's isCreative hook");
        var mixin = read("com/habitrain/core/client/mixin/WrathDoorAttackMixin");
        AnnotationNode wrap = mixin.methods.stream().filter(m -> m.visibleAnnotations != null)
                .flatMap(m -> m.visibleAnnotations.stream())
                .filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        var at = (AnnotationNode) ((java.util.List<?>) value(wrap, "at")).getFirst();
        assertNotNull(guard);
        assertEquals("L" + guard.owner + ";" + guard.name + guard.desc, value(at, "target"));
    }

    private static Object value(AnnotationNode node, String key) {
        for (int i = 0; i < node.values.size(); i += 2) {
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = WrathDoorAttackCompatibilityTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
