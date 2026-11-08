package com.habitrain.core.api;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Check every compiled API class, including nested classes and constant reflection targets. */
class ApiLayerDependencyGuardTest {
    private static final String CORE = "com/habitrain/core/";
    private static final String API = CORE + "api/";

    @Test
    void apiBytecodeNeverDependsOnImplementationLayers() throws Exception {
        Path classes = Path.of(GameModeRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path api = classes.resolve(API);
        assertTrue(Files.isDirectory(api), "compiled API classes missing: " + api);
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(api)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                for (String dependency : implementationReferences(Files.readAllBytes(file))) {
                    violations.add(api.relativize(file) + " -> " + dependency);
                }
            }
        }
        assertTrue(violations.isEmpty(), "public API depends on implementation: " + violations);
    }

    @Test
    void guardDetectsNewPackagesRootClassesDescriptorsAndLongReflectionConstants() {
        ClassWriter writer = fixture();
        writer.visitField(Opcodes.ACC_PRIVATE, "service", "Lcom/habitrain/core/future/Service;", null, null).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, CORE + "FutureRoot", "run", "()V", false);
        method.visitLdcInsn("x".repeat(800) + "com.habitrain.core.future.Reflected");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        assertEquals(Set.of(CORE + "future/Service", CORE + "FutureRoot", CORE + "future/Reflected"),
                implementationReferences(writer.toByteArray()));
    }

    @Test
    void guardAllowsPublicApiAndExternalTypes() {
        ClassWriter writer = fixture();
        writer.visitField(Opcodes.ACC_PRIVATE, "api", "Lcom/habitrain/core/api/TaskDefinition;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "external", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitEnd();
        assertTrue(implementationReferences(writer.toByteArray()).isEmpty());
    }

    private static ClassWriter fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, API + "Fixture", null, "java/lang/Object", null);
        return writer;
    }

    private static Set<String> implementationReferences(byte[] bytes) {
        Set<String> references = new TreeSet<>();
        String className = new ClassReader(bytes).getClassName();
        Remapper remapper = new Remapper() {
            @Override public String map(String name) {
                if (name.startsWith(CORE) && !name.startsWith(API)) references.add(name);
                return name;
            }
            @Override public Object mapValue(Object value) {
                if (value instanceof String text) {
                    // Compiled constant concatenations are one LDC value; there is no distance window.
                    var names = java.util.regex.Pattern.compile("com[./]habitrain[./]core[./][A-Za-z0-9_$/\\.]+")
                            .matcher(text);
                    while (names.find()) {
                        String name = names.group().replace('.', '/');
                        // An exact owner-name allowlist protects the lifecycle probe; it never loads that class.
                        // Type references and the same string in any other API class remain forbidden.
                        if (className.equals(API + "spi/CoreLifecycle")
                                && name.equals(CORE + "internal/CoreLifecycleScope")) continue;
                        map(name);
                    }
                }
                return super.mapValue(value);
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), remapper),
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return references;
    }

    @Test
    void apiSpiPackageIsTheOnlyBridgeToImplementation() throws Exception {
        Path spi = Path.of("src/main/java/com/habitrain/core/api/spi");
        for (String name : new String[]{"CoreSpi", "CoreLifecycle", "SreRuntimeBridge", "TaskPoolCacheBridge",
                "ExtraSlotReclaimBridge", "SceneInstanceBridge", "SceneRuntimeBridge", "SceneAssetBridge",
                "SceneCaptureBridge", "SceneConfigBridge", "SceneClientBridge", "MenuGateBridge",
                "MenuGateClientBridge", "VoteBridge", "RestAreaBridge", "RoleVisibilityBridge"}) {
            assertTrue(Files.isRegularFile(spi.resolve(name + ".java")), name);
        }
        assertFalse(Files.exists(spi.resolve("CoreLifecycleScope.java")));
    }

    @Test
    void apiPackageExposesNoPublicTestHooks() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java/com/habitrain/core/api"))) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String hook : new String[]{"unfreezeForTests", "resetTickGuards", "clearForTests"}) {
                    if (text.matches("(?s).*public\\s+static\\s+[\\w<>\\[\\], ]+\\s+" + hook + "\\s*\\(.*")) {
                        offenders.add(source + " -> " + hook);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "API exposes mutable test hooks: " + offenders);
    }
}
