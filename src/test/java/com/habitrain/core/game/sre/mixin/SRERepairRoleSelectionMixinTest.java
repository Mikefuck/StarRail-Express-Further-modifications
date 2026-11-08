package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class SRERepairRoleSelectionMixinTest {

    private static final String NEW_CTOR_TARGET = "(Ljava/util/Collection;)Ljava/util/ArrayList;";
    private static final String PLAYERS_TARGET = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;";

    @Test
    void beginNewRedirectUsesCtorReturnTypeNotInitVoid() throws Exception {
        Method handler = SRERepairRoleSelectionMixin.class.getDeclaredMethod(
                "habitrain$filterRepairersFromShuffle", Collection.class);
        Redirect redirect = handler.getAnnotation(Redirect.class);
        assertNotNull(redirect);
        At at = redirect.at();
        assertEquals("NEW", at.value());
        assertEquals(NEW_CTOR_TARGET, at.target());
    }

    @Test
    void mixinNewCtorTargetResolvesToArrayListNotVoid() {
        MemberInfo wrong = MemberInfo.parse(
                "Ljava/util/ArrayList;<init>(Ljava/util/Collection;)V", null);
        assertEquals("V", wrong.toCtorType());

        MemberInfo correct = MemberInfo.parse(NEW_CTOR_TARGET, null);
        assertEquals("java/util/ArrayList", correct.toCtorType());
        assertEquals("(Ljava/util/Collection;)V", correct.toCtorDesc());
    }

    @Test
    void upstreamRepairRoleSelectionContainsAllThreeRedirectCallSites() throws Exception {
        ClassNode target = read("net/exmo/sre/repair/logic/RepairRoleSelection");

        MethodNode begin = findMethod(target, "begin");
        boolean hasNewArrayList = false;
        boolean hasCollectionInit = false;
        for (int i = 0; i < begin.instructions.size(); i++) {
            var insn = begin.instructions.get(i);
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.NEW
                    && "java/util/ArrayList".equals(typeInsn.desc)) {
                hasNewArrayList = true;
            }
            if (insn instanceof MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                    && "java/util/ArrayList".equals(methodInsn.owner)
                    && "<init>".equals(methodInsn.name)
                    && "(Ljava/util/Collection;)V".equals(methodInsn.desc)) {
                hasCollectionInit = true;
            }
        }
        assertTrue(hasNewArrayList && hasCollectionInit,
                "RepairRoleSelection.begin must construct new ArrayList(Collection) for SRERepairRoleSelectionMixin");

        for (String methodName : new String[]{"reopenRoleSelection", "finalizeSelectedRoles"}) {
            MethodNode method = findMethod(target, methodName);
            boolean callsLevelPlayers = false;
            for (int i = 0; i < method.instructions.size(); i++) {
                if (method.instructions.get(i) instanceof MethodInsnNode call
                        && "net/minecraft/server/level/ServerLevel".equals(call.owner)
                        && "players".equals(call.name)
                        && "()Ljava/util/List;".equals(call.desc)) {
                    callsLevelPlayers = true;
                    break;
                }
            }
            assertTrue(callsLevelPlayers,
                    "RepairRoleSelection." + methodName + " must invoke " + PLAYERS_TARGET);
        }
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        return node.methods.stream()
                .filter(m -> name.equals(m.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing method " + name + " in " + node.name));
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = SRERepairRoleSelectionMixinTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
