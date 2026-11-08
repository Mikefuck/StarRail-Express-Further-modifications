package com.habitrain.core.game.sre.mixin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Named-bytecode preflight, not a substitute for production Mixin application. */
class SafetyMixinInjectionPointTest {
    @Test
    void requiredSafetyMixinsResolveTheirActualInstructionPoints() throws Exception {
        int handlers = 0;
        for (String config : List.of("habitrain_core.guards.mixins.json", "habitrain_core.rest_area.mixins.json")) {
            var loader = getClass().getClassLoader();
            try (var in = loader.getResourceAsStream(config)) {
                assertNotNull(in, config);
                var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(json.get("required").getAsBoolean(), config);
                assertTrue(json.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1, config);
                String pkg = json.get("package").getAsString().replace('.', '/');
                for (var entry : json.getAsJsonArray("mixins")) {
                    ClassNode mixin = read(pkg + "/" + entry.getAsString());
                    List<String> targets = targets(mixin);
                    assertFalse(targets.isEmpty(), mixin.name);
                    for (MethodNode handler : mixin.methods) {
                        for (AnnotationNode injection : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                            if (!injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                                    && !injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) continue;
                            String label = mixin.name + "#" + handler.name;
                            assertNotEquals(0, value(injection, "require"), label + " must not silently disable a guard");
                            assertNull(value(injection, "slice"), label + ": add explicit slice-aware validation");
                            @SuppressWarnings("unchecked") var selectors = (List<String>) value(injection, "method");
                            assertNotNull(selectors, label);
                            Object rawAt = value(injection, "at");
                            List<?> points = rawAt instanceof List<?> list ? list : List.of(rawAt);
                            for (String target : targets) {
                                for (String selector : selectors) {
                                    List<MethodNode> methods = methods(read(target), selector);
                                    assertFalse(methods.isEmpty(), label + " -> " + target + "#" + selector);
                                    for (Object point : points) {
                                        AnnotationNode at = (AnnotationNode) point;
                                        int count = methods.stream().mapToInt(m -> matchingInstructions(m, at)).sum();
                                        int ordinal = value(at, "ordinal") instanceof Integer i ? i : -1;
                                        assertTrue(count >= Math.max(1, ordinal + 1), label + ": missing "
                                                + value(at, "value") + " " + value(at, "target") + " ordinal=" + ordinal);
                                    }
                                }
                            }
                            handlers++;
                        }
                    }
                }
            }
        }
        assertTrue(handlers >= 20, "No meaningful safety injection coverage: " + handlers);
    }

    @Test
    void instructionGuardRejectsAChangedInvocationDescriptor() {
        MethodNode method = new MethodNode();
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "owner/Type", "run", "()V", false));
        AnnotationNode point = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        point.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lowner/Type;run()V"));
        assertEquals(1, matchingInstructions(method, point));
        point.values.set(3, "Lowner/Type;run(I)V");
        assertEquals(0, matchingInstructions(method, point));
    }

    private static int matchingInstructions(MethodNode method, AnnotationNode at) {
        String kind = (String) value(at, "value");
        String target = (String) value(at, "target");
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if ("HEAD".equals(kind) && opcode >= 0) return 1;
            if (("RETURN".equals(kind) || "TAIL".equals(kind)) && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) count++;
            if ("INVOKE".equals(kind) && instruction instanceof MethodInsnNode call
                    && ("L" + call.owner + ";" + call.name + call.desc).equals(target)) count++;
            if ("NEW".equals(kind) && instruction instanceof MethodInsnNode call && call.name.equals("<init>")) {
                Type object = Type.getReturnType(target);
                String constructor = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(target));
                if (call.owner.equals(object.getInternalName()) && call.desc.equals(constructor)) count++;
            }
        }
        if (!List.of("HEAD", "RETURN", "TAIL", "INVOKE", "NEW").contains(kind)) {
            fail("Unsupported injection point; extend preflight before accepting it: " + kind);
        }
        return count;
    }

    private static List<MethodNode> methods(ClassNode type, String selector) throws Exception {
        List<MethodNode> found = type.methods.stream()
                .filter(m -> selector.equals(m.name) || selector.equals(m.name + m.desc)).toList();
        if (!found.isEmpty() || type.superName == null || type.superName.equals("java/lang/Object")) return found;
        return methods(read(type.superName), selector);
    }

    private static List<String> targets(ClassNode type) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations(type.visibleAnnotations, type.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            if (value(annotation, "value") instanceof List<?> types) for (Object target : types) targets.add(((Type) target).getInternalName());
            if (value(annotation, "targets") instanceof List<?> names) for (Object target : names) targets.add(target.toString().replace('.', '/'));
        }
        return targets;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var in = SafetyMixinInjectionPointTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, name);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
