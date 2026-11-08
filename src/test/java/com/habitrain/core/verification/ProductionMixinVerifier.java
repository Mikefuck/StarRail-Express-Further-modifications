package com.habitrain.core.verification;

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

/** Inspect post-Mixin exports against the actual remapped release JAR. Run after real gameplay. */
public final class ProductionMixinVerifier {
    private ProductionMixinVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected production JAR and isolated runtime directory");
        Path release = Path.of(args[0]);
        Path runtime = Path.of(args[1]);
        Path exportRoot = runtime.resolve(".mixin.out/class");
        if (!Files.isDirectory(exportRoot)) {
            throw new IllegalStateException("No post-Mixin class exports. Launch this isolated runtime with "
                    + "-Dmixin.debug.export=true, exercise the safety scenarios, then run this verifier again.");
        }
        byte[] releaseDigest = digest(release);
        try (var files = Files.list(runtime.resolve("mods"))) {
            long matching = 0;
            for (Path file : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                if (Arrays.equals(releaseDigest, digest(file))) matching++;
            }
            if (matching != 1) throw new IllegalStateException("Runtime must contain exactly one copy of the current release JAR");
        }
        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (var jar = new ZipFile(release.toFile())) {
            for (String config : List.of("habitrain_core.guards.mixins.json", "habitrain_core.rest_area.mixins.json")) {
                var entry = jar.getEntry(config);
                if (entry == null) throw new IllegalStateException("Missing required config: " + config);
                try (var input = jar.getInputStream(entry)) {
                    var json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!json.get("required").getAsBoolean()) throw new IllegalStateException("Config is optional: " + config);
                    String pkg = json.get("package").getAsString().replace('.', '/');
                    for (var name : json.getAsJsonArray("mixins")) {
                        var mixinEntry = jar.getEntry(pkg + "/" + name.getAsString() + ".class");
                        if (mixinEntry == null) throw new IllegalStateException("Missing release mixin: " + name);
                        ClassNode mixin;
                        try (var bytes = jar.getInputStream(mixinEntry)) { mixin = read(bytes.readAllBytes()); }
                        List<String> targets = targets(mixin);
                        if (targets.isEmpty()) throw new IllegalStateException("Mixin has no targets: " + mixin.name);
                        for (String target : targets) {
                            Path exported = exportRoot.resolve(target + ".class");
                            if (!Files.isRegularFile(exported)) {
                                missing.add(mixin.name + " -> target not exercised/exported: " + target);
                                continue;
                            }
                            ClassNode transformed = read(Files.readAllBytes(exported));
                            for (var method : mixin.methods) {
                                boolean injector = annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
                                        .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                                                || a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"));
                                if (!injector) continue;
                                // Mixin prefixes handler names to avoid clashes; the original name and descriptor remain.
                                boolean applied = transformed.methods.stream().anyMatch(actual ->
                                        actual.name.contains(method.name) && actual.desc.equals(method.desc));
                                if (!applied) missing.add(mixin.name + "#" + method.name + " absent in " + target);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        if (!missing.isEmpty()) throw new IllegalStateException("Production safety Mixin evidence incomplete:\n" + String.join("\n", missing));
        if (checked == 0) throw new IllegalStateException("No handlers verified");
        System.out.println("Verified " + checked + " safety handlers in post-Mixin production exports. Gameplay behavior still requires scenario acceptance.");
    }

    private static byte[] digest(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static List<String> targets(ClassNode type) {
        List<String> targets = new ArrayList<>();
        for (var annotation : annotations(type.visibleAnnotations, type.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            if (value(annotation, "value") instanceof List<?> types) for (Object target : types) targets.add(((Type) target).getInternalName());
            if (value(annotation, "targets") instanceof List<?> names) for (Object target : names) targets.add(target.toString().replace('.', '/'));
        }
        return targets;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (visible != null) annotations.addAll(visible);
        if (invisible != null) annotations.addAll(invisible);
        return annotations;
    }
}
