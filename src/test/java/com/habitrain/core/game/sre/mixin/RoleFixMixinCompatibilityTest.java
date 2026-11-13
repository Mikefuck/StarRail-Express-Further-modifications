package com.habitrain.core.game.sre.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Compile success alone cannot detect a renamed mixin target in the shipped dependency. */
class RoleFixMixinCompatibilityTest {

    private static final Set<String> INJECTOR_DESCS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;"
    );

    @ParameterizedTest
    @CsvSource({
            "EnvyDeathLootMixin, io/wifi/starrailexpress/api/GameMode",
            "EnvyGunDropMixin, io/wifi/starrailexpress/util/BrokenGunDropUtils",
            "GreedPouchInsertMixin, net/minecraft/world/item/BundleItem",
            "SlothSleepHitboxMixin, net/minecraft/world/entity/player/Player",
            "SlothMinigameTaskMixin, io/wifi/starrailexpress/cca/SREPlayerMinigameTaskComponent",
            "WrathMeleeAttackMixin, net/minecraft/server/network/ServerGamePacketListenerImpl",
            "WrathMeleeDamageMixin, net/minecraft/world/entity/player/Player",
            "WrathNunchuckHitMixin, io/wifi/starrailexpress/network/original/NunchuckHitPayload",
            "SwiftWindPsychoMixin, io/wifi/starrailexpress/cca/SREPlayerPsychoComponent",
            "GenerateTaskMixin, io/wifi/starrailexpress/cca/SREPlayerTaskComponent",
            "RoleShopHookMixin, io/wifi/starrailexpress/cca/SREPlayerShopComponent",
            "LustDeathEventsMixin, org/agmas/noellesroles/init/events/NRDeathEvents",
            "LustDeathPenaltyMixin, org/agmas/noellesroles/component/DeathPenaltyComponent",
            "LustRevivalMixin, io/wifi/starrailexpress/game/GameUtils",
            "RefugeeRestorationMixin, pro/fazeclan/river/stupid_express/modifier/refugee/cca/PlayerStatsBeforeRefugee"
    })
    void injectionSignaturesMatchActualMinecraftAndSre(String mixinName, String targetName) throws Exception {
        ClassNode mixin = read("com/habitrain/core/game/sre/mixin/" + mixinName);
        ClassNode target = read(targetName);
        int checked = 0;
        for (var handler : mixin.methods) {
            if (handler.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : handler.visibleAnnotations) {
                boolean wrapMethod = annotation.desc.equals(
                        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
                if (!wrapMethod && !annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
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
                    assertEquals(wrapMethod ? "Operation" : returnsValue ? "CallbackInfoReturnable" : "CallbackInfo",
                            parameters[parameters.length - 1].getClassName().replaceAll(".*\\.", ""));
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "No injections checked for " + mixinName);
    }

    @Test
    void roleShopSuccessHookUsesTheSuccessfulPurchaseWrite() throws Exception {
        String owner = "io/wifi/starrailexpress/cca/SREPlayerShopComponent";
        ClassNode target = read(owner);
        var tryBuy = target.methods.stream()
                .filter(method -> "tryBuy".equals(method.name) && "(I)V".equals(method.desc))
                .findFirst()
                .orElseThrow();
        long totalCostWrites = Arrays.stream(tryBuy.instructions.toArray())
                .filter(instruction -> instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && owner.equals(field.owner)
                        && "total_cost".equals(field.name)
                        && "I".equals(field.desc))
                .count();
        assertEquals(1, totalCostWrites, "tryBuy should have one success-path total_cost write");

        ClassNode mixin = read("com/habitrain/core/game/sre/mixin/RoleShopHookMixin");
        var handler = mixin.methods.stream()
                .filter(method -> "habitrain$v2CapturePurchaseSuccess".equals(method.name))
                .findFirst()
                .orElseThrow();
        AnnotationNode inject = handler.visibleAnnotations.stream()
                .filter(annotation -> "Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotation.desc))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<AnnotationNode> atValues = (List<AnnotationNode>) value(inject, "at");
        assertNotNull(atValues);
        assertEquals(1, atValues.size());
        AnnotationNode at = atValues.getFirst();
        assertEquals("FIELD", value(at, "value"));
        assertEquals("L" + owner + ";total_cost:I", value(at, "target"));
        assertEquals(Opcodes.PUTFIELD, value(at, "opcode"));
        assertEquals(1, value(inject, "require"));
        Object shift = value(at, "shift");
        assertInstanceOf(String[].class, shift);
        assertEquals("AFTER", ((String[]) shift)[1]);
    }

    @Test
    void allConfiguredMixinsAreRequiredAndResolveTargetsInBytecode() throws Exception {
        String[] configs = {
                "/habitrain_core.mixins.json",
                "/habitrain_core.guards.mixins.json",
                "/habitrain_core.rest_area.mixins.json",
                "/habitrain_core.client.mixins.json"
        };
        int totalMixinsChecked = 0;
        for (String configPath : configs) {
            JsonObject json = readJson(configPath);
            assertTrue(json.has("required") && json.get("required").getAsBoolean(),
                    configPath + " must set top-level \"required\": true");
            String pkg = json.get("package").getAsString().replace('.', '/');
            for (String section : new String[]{"mixins", "client", "server"}) {
                if (!json.has(section)) continue;
                for (JsonElement el : json.getAsJsonArray(section)) {
                    String relName = el.getAsString().replace('.', '/');
                    ClassNode mixin = read(pkg + "/" + relName);
                    List<String> targetNames = extractMixinTargetInternalNames(mixin);
                    assertFalse(targetNames.isEmpty(), configPath + " -> " + relName + " has no @Mixin target");
                    List<ClassNode> targets = new ArrayList<>();
                    for (String targetName : targetNames) targets.add(read(targetName));
                    verifyInjectorSelectorsExist(configPath + ":" + relName, mixin, targets);
                    totalMixinsChecked++;
                }
            }
        }
        assertTrue(totalMixinsChecked >= 120, "Expected to verify all configured Mixin classes, got " + totalMixinsChecked);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractMixinTargetInternalNames(ClassNode mixin) {
        List<String> out = new ArrayList<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (mixin.visibleAnnotations != null) annotations.addAll(mixin.visibleAnnotations);
        if (mixin.invisibleAnnotations != null) annotations.addAll(mixin.invisibleAnnotations);
        for (AnnotationNode ann : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) continue;
            List<Type> types = (List<Type>) value(ann, "value");
            if (types != null) {
                for (Type t : types) out.add(t.getInternalName());
            }
            List<String> targets = (List<String>) value(ann, "targets");
            if (targets != null) {
                for (String s : targets) out.add(s.replace('.', '/'));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void verifyInjectorSelectorsExist(String label, ClassNode mixin, List<ClassNode> targets) throws Exception {
        for (MethodNode handler : mixin.methods) {
            if (handler.visibleAnnotations == null) continue;
            for (AnnotationNode ann : handler.visibleAnnotations) {
                if (!INJECTOR_DESCS.contains(ann.desc)) continue;
                List<String> selectors = new ArrayList<>();
                List<String> literalSelectors = (List<String>) value(ann, "method");
                if (literalSelectors != null) selectors.addAll(literalSelectors);
                List<AnnotationNode> descriptors = (List<AnnotationNode>) value(ann, "target");
                if (descriptors != null) for (AnnotationNode desc : descriptors) {
                    String name = (String) value(desc, "value");
                    Type result = (Type) value(desc, "ret");
                    List<Type> args = (List<Type>) value(desc, "args");
                    selectors.add(name + Type.getMethodDescriptor(result == null ? Type.VOID_TYPE : result,
                            args == null ? new Type[0] : args.toArray(Type[]::new)));
                }
                assertFalse(selectors.isEmpty(), label + "#" + handler.name + " missing method selector");
                for (String rawSelector : selectors) {
                    if (Integer.valueOf(0).equals(value(ann, "require")) && targets.size() > 1) {
                        boolean matched = false;
                        for (ClassNode target : targets) matched |= matchesMethodInHierarchy(target, rawSelector);
                        assertTrue(matched, label + "#" + handler.name + " optional selector '" + rawSelector
                                + "' not found in any declared target");
                    } else {
                        for (ClassNode target : targets) {
                            assertTrue(matchesMethodInHierarchy(target, rawSelector),
                                    label + "#" + handler.name + " selector '" + rawSelector
                                            + "' not found in " + target.name + " hierarchy");
                        }
                    }
                }
            }
        }
    }

    private static boolean matchesMethodInHierarchy(ClassNode target, String rawSelector) throws Exception {
        if ("*".equals(rawSelector)) {
            return target.methods.stream().anyMatch(method -> !method.name.startsWith("<"));
        }
        String selector = rawSelector;
        int semi = selector.indexOf(';');
        if (selector.startsWith("L") && semi > 0 && selector.indexOf('(') > semi) {
            selector = selector.substring(semi + 1);
        }
        ClassNode cursor = target;
        while (cursor != null) {
            for (MethodNode m : cursor.methods) {
                if (selector.startsWith("(")) {
                    if (m.desc.equals(selector)) return true;
                } else {
                    int paren = selector.indexOf('(');
                    if (paren > 0) {
                        String name = selector.substring(0, paren);
                        String desc = selector.substring(paren);
                        if (m.name.equals(name) && m.desc.equals(desc)) return true;
                    } else if (m.name.equals(selector)) {
                        return true;
                    }
                }
            }
            if (cursor.superName == null || "java/lang/Object".equals(cursor.superName)) {
                break;
            }
            try (var stream = RoleFixMixinCompatibilityTest.class.getClassLoader()
                    .getResourceAsStream(cursor.superName + ".class")) {
                if (stream == null) break;
                ClassNode next = new ClassNode();
                new ClassReader(stream).accept(next, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                cursor = next;
            }
        }
        return false;
    }

    private static JsonObject readJson(String resourcePath) throws Exception {
        try (var stream = RoleFixMixinCompatibilityTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(stream, resourcePath);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
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
