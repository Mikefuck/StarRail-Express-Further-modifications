package com.habitrain.core.scene.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SceneLanguageFormatTest {
    private static final Pattern UNSUPPORTED_FORMAT = Pattern.compile("%(?:\\d+\\$)?(?:\\.\\d+)?[df]");
    private static final Pattern MC_FORMAT_TOKEN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");
    private static final Pattern STRING_PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?s");

    @Test
    void allLanguageTablesUseValidMinecraftPlaceholdersAndNoBom() throws Exception {
        var paths = languageTables();
        for (Path path : paths) {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(raw.startsWith("\uFEFF"), () -> path + " must not start with UTF-8 BOM");
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue().getAsString();
                assertFalse(UNSUPPORTED_FORMAT.matcher(value).find(),
                        () -> path + " uses unsupported %d/%f placeholder in " + key + ": " + value);
                Matcher matcher = MC_FORMAT_TOKEN.matcher(value);
                while (matcher.find()) {
                    String token = matcher.group();
                    assertTrue("%%".equals(token) || STRING_PLACEHOLDER.matcher(token).matches(),
                            () -> path + " has invalid TranslatableContents token '" + token
                                    + "' in " + key + ": " + value);
                }
            }
        }
    }

    @Test
    void habitrainCoreLanguageTablesHaveSymmetricKeysAndPlaceholderCounts() throws Exception {
        JsonObject zh = readJson(Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "zh_cn.json"));
        JsonObject en = readJson(Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "en_us.json"));

        for (String key : zh.keySet()) {
            assertTrue(en.has(key), () -> "en_us.json is missing key: " + key);
            int zhCount = countPlaceholders(zh.get(key).getAsString());
            int enCount = countPlaceholders(en.get(key).getAsString());
            assertEquals(zhCount, enCount,
                    () -> "Mismatched %s placeholder count for " + key + ": zh=" + zhCount + ", en=" + enCount);
        }
        for (String key : en.keySet()) {
            assertTrue(zh.has(key), () -> "zh_cn.json is missing key: " + key);
        }
    }

    @Test
    void rootStarRailExpressLanguageTablesMatchUpstreamPlaceholderContracts() throws Exception {
        JsonObject zh = readJson(Path.of("assets", "starrailexpress", "lang", "zh_cn.json"));
        JsonObject en = readJson(Path.of("assets", "starrailexpress", "lang", "en_us.json"));

        assertTrue(zh.has("gui.sre.role_rotation.selected"));
        assertEquals(1, countPlaceholders(zh.get("gui.sre.role_rotation.selected").getAsString()),
                "gui.sre.role_rotation.selected must have exactly 1 %s placeholder to match upstream 4.3.0");

        for (String key : en.keySet()) {
            assertTrue(zh.has(key), () -> "assets/starrailexpress/lang/zh_cn.json is missing key: " + key);
            assertEquals(
                    countPlaceholders(zh.get(key).getAsString()),
                    countPlaceholders(en.get(key).getAsString()),
                    () -> "Mismatched %s count in starrailexpress lang for " + key);
        }
    }

    @Test
    void compatibilityIssueLabelsExistInBothLanguages() throws Exception {
        Set<String> suffixes = Set.of(
                "none", "missing_texture", "invalid_atlas", "abnormal_packed_light",
                "missing_adapter", "unsupported_render_path", "skipped");
        for (String language : new String[]{"zh_cn", "en_us"}) {
            Path path = Path.of("src", "main", "resources", "assets", "habitrain_core",
                    "lang", language + ".json");
            JsonObject root = readJson(path);
            for (String suffix : suffixes) {
                String key = "screen.habitrain_core.scene_motion.compat_issue_" + suffix;
                assertTrue(root.has(key), () -> language + " is missing " + key);
                assertFalse(root.get(key).getAsString().isBlank(), () -> language + " has blank " + key);
            }
        }
    }

    @Test
    void literalTranslationCallsResolveInBothLanguages() throws Exception {
        var zh = readJson(Path.of("src/main/resources/assets/habitrain_core/lang/zh_cn.json"));
        var en = readJson(Path.of("src/main/resources/assets/habitrain_core/lang/en_us.json"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Translation call-site validation requires a JDK");
        var problems = new java.util.ArrayList<String>();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files = Files.walk(Path.of("src/main/java"));
             var manager = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList());
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, diagnostics,
                    java.util.List.of("-proc:none"), null, units);
            int[] checked = {0};
            for (var unit : task.parse()) {
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree call, Void unused) {
                        String method = call.getMethodSelect().toString();
                        if ((method.endsWith(".translatable") || method.endsWith(".translatableWithFallback"))
                                && !call.getArguments().isEmpty()
                                && call.getArguments().getFirst() instanceof com.sun.source.tree.LiteralTree literal
                                && literal.getValue() instanceof String key && key.contains(".habitrain_core.")) {
                            checked[0]++;
                            if (!zh.has(key) || !en.has(key)) problems.add(unit.getSourceFile().getName() + " -> " + key);
                        }
                        return super.visitMethodInvocation(call, unused);
                    }
                }.scan(unit, null);
            }
            assertTrue(checked[0] > 100, "No meaningful Java translation call coverage: " + checked[0]);
        }
        assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR),
                () -> "Cannot parse Java call sites: " + diagnostics.getDiagnostics());
        assertTrue(problems.isEmpty(), "Missing translations at Java call sites: " + problems);
    }

    private static java.util.List<Path> languageTables() throws Exception {
        var tables = new java.util.ArrayList<Path>();
        for (Path root : java.util.List.of(Path.of("src/main/resources/assets"), Path.of("assets"))) {
            try (var files = Files.walk(root)) {
                tables.addAll(files.filter(p -> p.toString().endsWith(".json")
                        && p.getParent().getFileName().toString().equals("lang")).toList());
            }
        }
        assertTrue(tables.size() >= 4, "Language resource discovery failed");
        return tables;
    }

    private static int countPlaceholders(String text) {
        String withoutEscapedPercent = text.replace("%%", "");
        Matcher m = STRING_PLACEHOLDER.matcher(withoutEscapedPercent);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static JsonObject readJson(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
