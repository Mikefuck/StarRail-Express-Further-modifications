package com.habitrain.core.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrainRenderDisconnectCompatibilityTest {
    private static final Map<Class<?>, String> TARGETS = Map.of(
            WheelRenderDisconnectMixin.class,
            "io/wifi/starrailexpress/client/render/block_entity/WheelBlockEntityRenderer",
            SREClientTrainStateMixin.class, "io/wifi/starrailexpress/client/SREClient");

    @Test
    void everyReceiverGuardMatchesTheActualSreCallSite() throws Exception {
        int checked = 0;
        for (var entry : TARGETS.entrySet()) {
            ClassNode target = read(entry.getValue());
            for (Method handler : entry.getKey().getDeclaredMethods()) {
                WrapOperation wrap = handler.getAnnotation(WrapOperation.class);
                if (wrap == null) continue;
                for (String selector : wrap.method()) {
                    var methods = target.methods.stream().filter(m -> selector.equals(m.name)
                            || selector.equals(m.name + m.desc)).toList();
                    assertFalse(methods.isEmpty(), selector);
                    int calls = 0;
                    for (var method : methods) {
                        for (var instruction : method.instructions) {
                            if (instruction instanceof MethodInsnNode call
                                    && wrap.at()[0].target().equals("L" + call.owner + ";" + call.name + call.desc)) {
                                calls++;
                            }
                        }
                    }
                    assertEquals(1, calls, selector + " must guard the actual dereference");
                    checked++;
                }
            }
        }
        assertEquals(4, checked);
    }

    @Test
    void clearedCachesReturnIdleValuesWithoutDereferencingNull() throws Exception {
        Operation<Object> unreachable = args -> {
            fail("A disconnected renderer must not invoke a method on the cleared component");
            return null;
        };
        int checked = 0;
        for (Class<?> mixin : TARGETS.keySet()) {
            for (Method handler : mixin.getDeclaredMethods()) {
                if (handler.getAnnotation(WrapOperation.class) == null) continue;
                handler.setAccessible(true);
                Object value = handler.invoke(null, null, unreachable);
                if (handler.getReturnType() == float.class) assertEquals(0.0F, value);
                else if (handler.getReturnType() == int.class) assertEquals(0, value);
                else assertEquals(false, value);
                checked++;
            }
        }
        assertEquals(3, checked);
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = TrainRenderDisconnectCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
