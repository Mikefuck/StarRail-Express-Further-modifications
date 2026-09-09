package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WrathNoisemakerKnockbackMixinTest {
    private static final String NOISEMAKER =
            "org/agmas/noellesroles/game/roles/innocence/noise_maker/NoiseMakerPlayerComponent";

    @Test
    void redirectsMatchTheActualUpstreamDependency() throws Exception {
        // Inspect bytecode without initializing Minecraft's component registries.
        ClassNode upstream = readClass(NOISEMAKER);
        int redirects = 0;
        for (Method handler : WrathNoisemakerKnockbackMixin.class.getDeclaredMethods()) {
            Redirect redirect = handler.getAnnotation(Redirect.class);
            if (redirect == null) continue;
            redirects++;
            assertEquals(1, countCalls(upstream, "useAbility", redirect.at().target()),
                    "Upstream changed: " + redirect.at().target());
        }
        assertEquals(2, redirects);
    }

    @Test
    void slothWakeAngerStillUsesTheProtectedAbility() throws Exception {
        ClassNode sloth = readClass("com/habitrain/core/game/sre/role/sins/component/SlothComponent");
        assertEquals(1, countCalls(sloth, "wakeFromShieldBreak", "L" + NOISEMAKER + ";useAbility()V"));
    }

    private static ClassNode readClass(String name) throws Exception {
        try (InputStream stream = WrathNoisemakerKnockbackMixinTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static int countCalls(ClassNode node, String methodName, String target) {
        int count = 0;
        for (var method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && target.equals("L" + call.owner + ";" + call.name + call.desc)) count++;
            }
        }
        return count;
    }
}
