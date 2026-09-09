package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compile success alone cannot detect a renamed mixin target in the shipped dependency. */
class RoleFixMixinCompatibilityTest {
    @ParameterizedTest
    @CsvSource({
            "GreedPouchInsertMixin, net/minecraft/world/item/BundleItem",
            "SlothSleepHitboxMixin, net/minecraft/world/entity/player/Player",
            "SlothMinigameTaskMixin, io/wifi/starrailexpress/cca/SREPlayerMinigameTaskComponent",
            "WrathMeleeAttackMixin, net/minecraft/server/network/ServerGamePacketListenerImpl",
            "WrathMeleeDamageMixin, net/minecraft/world/entity/player/Player",
            "WrathNunchuckHitMixin, io/wifi/starrailexpress/network/original/NunchuckHitPayload",
            "SwiftWindPsychoMixin, io/wifi/starrailexpress/cca/SREPlayerPsychoComponent",
            "GenerateTaskMixin, io/wifi/starrailexpress/cca/SREPlayerTaskComponent"
    })
    void injectionSignaturesMatchActualMinecraftAndSre(String mixinName, String targetName) throws Exception {
        ClassNode mixin = read("com/habitrain/core/game/sre/mixin/" + mixinName);
        ClassNode target = read(targetName);
        int checked = 0;
        for (var handler : mixin.methods) {
            if (handler.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : handler.visibleAnnotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                @SuppressWarnings("unchecked")
                List<String> selectors = (List<String>) value(annotation, "method");
                assertNotNull(selectors);
                Type[] parameters = Type.getArgumentTypes(handler.desc);
                Type[] captured = Arrays.copyOf(parameters, parameters.length - 1);
                for (String selector : selectors) {
                    var matches = target.methods.stream().filter(method -> selector.equals(method.name)
                            || selector.equals(method.name + method.desc)).toList();
                    assertEquals(1, matches.size(), mixinName + ": " + selector);
                    var method = matches.getFirst();
                    if (captured.length > 0) {
                        assertArrayEquals(Type.getArgumentTypes(method.desc), captured,
                                mixinName + " callback arguments for " + selector);
                    }
                    boolean returnsValue = Type.getReturnType(method.desc).getSort() != Type.VOID;
                    assertEquals(returnsValue ? "CallbackInfoReturnable" : "CallbackInfo",
                            parameters[parameters.length - 1].getClassName().replaceAll(".*\\.", ""));
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "No injections checked for " + mixinName);
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = RoleFixMixinCompatibilityTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
