package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SREBlackoutGlobalCooldownAnchorTest {

    @Test
    void upstreamJarRetainsTheGlobalAndPersonalCooldownAnchors() throws Exception {
        String resource = "/io/wifi/starrailexpress/cca/SREPlayerShopComponent.class";
        try (InputStream input = SREBlackoutGlobalCooldownAnchorTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "SREPlayerShopComponent must remain on the compile/runtime classpath");

            AtomicInteger fanOutCalls = new AtomicInteger();
            AtomicInteger personalCooldownCalls = new AtomicInteger();
            AtomicInteger fanOutCooldownCalls = new AtomicInteger();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    boolean target = name.equals("useBlackout")
                            && descriptor.equals("(Lnet/minecraft/world/entity/player/Player;I)Z");
                    boolean lambda = name.startsWith("lambda$useBlackout$");
                    if (!target && !lambda) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String invokedName,
                                                    String invokedDescriptor, boolean isInterface) {
                            if (target && owner.equals("java/util/List") && invokedName.equals("forEach")) {
                                fanOutCalls.incrementAndGet();
                            }
                            if (invokedName.equals("addCooldown")) {
                                if (target) personalCooldownCalls.incrementAndGet();
                                if (lambda) fanOutCooldownCalls.incrementAndGet();
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            assertEquals(1, fanOutCalls.get(), "the narrow List.forEach Mixin anchor changed");
            assertEquals(1, personalCooldownCalls.get(), "the user's personal cooldown path changed");
            assertEquals(1, fanOutCooldownCalls.get(), "the upstream fan-out lambda changed");
        }
    }
}
