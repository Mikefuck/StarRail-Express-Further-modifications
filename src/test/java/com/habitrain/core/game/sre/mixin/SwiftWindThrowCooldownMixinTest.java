package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;

import static org.junit.jupiter.api.Assertions.*;

class SwiftWindThrowCooldownMixinTest {
    @Test
    void selectorMatchesExactlyTheRegisteredThrowReceiverInTheActualDependency() throws Exception {
        Inject injection = null;
        for (var method : SwiftWindThrowCooldownMixin.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Inject.class)) injection = method.getAnnotation(Inject.class);
        }
        assertNotNull(injection);
        assertTrue(injection.cancellable());
        assertEquals("HEAD", injection.at()[0].value());
        var selector = MemberInfo.parse(injection.method()[0], null).validate();
        var upstream = new ClassNode();
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "org/agmas/noellesroles/init/ModPacketsReciever.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(upstream, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var matches = upstream.methods.stream()
                .filter(method -> selector.matches(upstream.name, method.name, method.desc).isExactMatch())
                .toList();
        assertEquals(1, matches.size(), "Injection must match exactly one upstream receiver");
        var receiver = matches.getFirst();
        assertTrue((receiver.access & Opcodes.ACC_STATIC) != 0);
        int references = 0;
        for (var method : upstream.methods) {
            if (!method.name.equals("registerPackets")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode dynamic)) continue;
                for (var argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getOwner().equals(upstream.name)
                            && handle.getName().equals(receiver.name) && handle.getDesc().equals(receiver.desc)) {
                        references++;
                    }
                }
            }
        }
        assertEquals(1, references, "Matched receiver must be wired into packet registration");
    }
}
