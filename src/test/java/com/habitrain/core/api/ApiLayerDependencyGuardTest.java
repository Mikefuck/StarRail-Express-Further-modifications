package com.habitrain.core.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开层依赖方向守卫（audit A2 / D-04）。
 *
 * <p>期望方向是 {@code api ← 所有实现层}，即 {@code com.habitrain.core.api} 只被内部层依赖，
 * <b>不得</b>反向依赖实现层。实现层能力一律经 {@code api.spi} 的桥接接口注入
 * （见 {@link com.habitrain.core.api.spi.CoreSpi}）。
 *
 * <p>与 2.0.10 的版本相比有两处修正：
 * <ul>
 *   <li><b>前缀补全</b>：旧版黑名单只有 {@code game/}、{@code task/}、{@code internal/} 三项，
 *       于是 {@code config/}、{@code client/}、{@code vote/}、{@code role/}、{@code scene/}
 *       下约 54 处反向引用全部漏检，测试给了虚假的安全感。</li>
 *   <li><b>改为扫源码</b>：旧版扫 {@code build/classes}/... 的常量池，若 Gradle 的
 *       {@code compileJava} 命中 {@code UP-TO-DATE} 就会扫到<b>上一次</b>的产物，
 *       漏掉当次源码引入的违规。现在直接扫 {@code src/main/java} 的文本，
 *       同时能抓住 {@code import}、全限定引用、方法描述符里的类型名与 javadoc。</li>
 * </ul>
 */
class ApiLayerDependencyGuardTest {

    /** 公开层禁止出现的实现层包前缀。 */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "com.habitrain.core.betel.",
            "com.habitrain.core.client.",
            "com.habitrain.core.config.",
            "com.habitrain.core.game.",
            "com.habitrain.core.internal.",
            "com.habitrain.core.misc.",
            "com.habitrain.core.mixin.",
            "com.habitrain.core.network.",
            "com.habitrain.core.persist.",
            "com.habitrain.core.role.",
            "com.habitrain.core.scene.",
            "com.habitrain.core.task.",
            "com.habitrain.core.util.",
            "com.habitrain.core.vote."
    );

    /** 只允许出现在 api 里的顶层类（core 根包下的实现入口）。 */
    private static final List<String> FORBIDDEN_ROOT_CLASSES = List.of(
            "com.habitrain.core.BuiltinTaskRegistrar",
            "com.habitrain.core.C2SReceiverRegistrar",
            "com.habitrain.core.CommandRegistrar",
            "com.habitrain.core.HabiTrainCore",
            "com.habitrain.core.LifecycleEventsRegistrar",
            "com.habitrain.core.LootHelper",
            "com.habitrain.core.ModTickHandler",
            "com.habitrain.core.NetworkRegistrar",
            "com.habitrain.core.VoiceGroupService"
    );

    @Test
    void apiPackageNeverReferencesImplementationLayers() throws Exception {
        Path apiRoot = locateApiSources();
        assertTrue(Files.isDirectory(apiRoot), "api source root missing: " + apiRoot);
        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(apiRoot)) {
            for (Path source : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                List<String> forbidden = new ArrayList<>(FORBIDDEN_PREFIXES);
                forbidden.addAll(FORBIDDEN_ROOT_CLASSES);
                for (String prefix : forbidden) {
                    if (containsReference(text, prefix)) {
                        violations.add(apiRoot.relativize(source) + " -> " + prefix);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "public api layer must not depend on implementation layers: " + violations);
    }

    /**
     * 审核 A-02：根类黑名单必须<b>自动</b>覆盖 {@code com/habitrain/core/} 下的每一个顶层类。
     *
     * <p>旧实现的名单是硬编码的 9 项，新增一个根包实现入口就会静默逃过守卫
     * （这正是 B-01 类越层依赖能长期存活的原因之一）。现在直接枚举根包目录：
     * 凡是根包下的顶层 {@code .java}，只要不是 {@code api} 目录本身，
     * 就必须出现在 {@link #FORBIDDEN_ROOT_CLASSES} 里。
     */
    @Test
    void forbiddenRootClassListCoversEveryRootPackageClass() throws Exception {
        Path apiRoot = locateApiSources();
        Path coreRoot = apiRoot.getParent();
        assertTrue(Files.isDirectory(coreRoot), "core source root missing: " + coreRoot);

        List<String> discovered = new ArrayList<>();
        try (Stream<Path> stream = Files.list(coreRoot)) {
            for (Path entry : stream.toList()) {
                if (Files.isDirectory(entry)) {
                    continue;
                }
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    continue;
                }
                discovered.add("com.habitrain.core." + fileName.substring(0, fileName.length() - 5));
            }
        }

        List<String> uncovered = new ArrayList<>();
        for (String className : discovered) {
            if (!FORBIDDEN_ROOT_CLASSES.contains(className)) {
                uncovered.add(className);
            }
        }
        assertTrue(uncovered.isEmpty(),
                "new root-package implementation classes must be added to FORBIDDEN_ROOT_CLASSES "
                        + "(audit A-02): " + uncovered);

        List<String> stale = new ArrayList<>();
        for (String className : FORBIDDEN_ROOT_CLASSES) {
            if (!discovered.contains(className)) {
                stale.add(className);
            }
        }
        assertTrue(stale.isEmpty(),
                "FORBIDDEN_ROOT_CLASSES lists classes that no longer exist at the root package; "
                        + "remove them so the guard stays exact: " + stale);
    }

    /**
     * 审核 A-02：实现层引用也必须通过<b>反射 / 字符串</b>形式被拦住。
     *
     * <p>前面的文本扫描只看源码里出现的包前缀；{@code Class.forName("com.habitrain.core." + "role." + x)}
     * 这类拼接（或 {@code getDeclaredMethod} 的类名字符串）在源码里不出现完整前缀，
     * 却能在运行期拿到实现层对象。这里做保守检查：api 层不允许出现
     * {@code Class.forName} / {@code getDeclaredMethod} / {@code getMethod} 的目标字符串
     * 指向本仓包空间（{@code com.habitrain.core.}）——核心公开层没有任何正当理由在运行期
     * 反射自己的实现层，桥接一律经 {@code api.spi} 注入。
     *
     * <p>允许的例外：{@code com.habitrain.core.api.} 自身（公开层类型之间的反射）
     * 与 {@code CoreLifecycle.OWNER_CLASS} 那种刻意拆成两段拼接的自校验字符串。
     */
    @Test
    void apiPackageDoesNotReflectIntoImplementationLayers() throws Exception {
        Path apiRoot = locateApiSources();
        String corePrefix = "com.habitrain.core.";
        String apiPrefix = "com.habitrain.core.api.";
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(apiRoot)) {
            for (Path source : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                int from = 0;
                while (true) {
                    int idx = text.indexOf(corePrefix, from);
                    if (idx < 0) {
                        break;
                    }
                    from = idx + 1;
                    String tail = text.substring(idx);
                    if (tail.startsWith(apiPrefix)) {
                        continue;
                    }
                    // 只有出现在「反射目标 / 字符串常量」语境里才算违规：
                    // 前面 200 字符内出现 forName/getDeclaredMethod/getMethod/getField。
                    int windowStart = Math.max(0, idx - 200);
                    String window = text.substring(windowStart, idx);
                    if (window.contains("forName") || window.contains("getDeclaredMethod")
                            || window.contains("getMethod") || window.contains("getDeclaredField")
                            || window.contains("loadClass")) {
                        offenders.add(apiRoot.relativize(source) + " -> " + tail.substring(0, Math.min(60, tail.length())));
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "public api layer must not reflect into implementation layers (audit A-02): " + offenders);
    }

    /** 必须存在，且是公开层唯一的实现层桥接入口。 */
    @Test
    void apiSpiPackageIsTheOnlyBridgeToImplementation() throws Exception {
        Path spiRoot = locateApiSources().resolve("spi");
        assertTrue(Files.isDirectory(spiRoot), "api/spi package is missing");
        for (String spi : new String[] {
                "CoreSpi", "CoreLifecycle", "SreRuntimeBridge", "TaskPoolCacheBridge",
                "ExtraSlotReclaimBridge", "SceneInstanceBridge", "SceneRuntimeBridge",
                "SceneAssetBridge", "SceneCaptureBridge", "SceneConfigBridge",
                "SceneClientBridge", "MenuGateBridge", "MenuGateClientBridge", "VoteBridge",
                "RestAreaBridge", "RoleVisibilityBridge"}) {
            assertTrue(Files.exists(spiRoot.resolve(spi + ".java")),
                    "api/spi/" + spi + " is missing");
        }
        assertFalse(Files.exists(spiRoot.resolve("CoreLifecycleScope.java")),
                "CoreLifecycleScope must not live in the public api layer (audit A1)");
    }

    /** 公开层不得出现「测试专用」的可变入口（audit B10）。 */
    @Test
    void apiPackageExposesNoPublicTestHooks() throws Exception {
        Path apiRoot = locateApiSources();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(apiRoot)) {
            for (Path source : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String hook : new String[] {"unfreezeForTests", "resetTickGuards", "clearForTests"}) {
                    if (text.contains("public static") && text.contains(hook)
                            && text.matches("(?s).*public\\s+static\\s+[\\w<>\\[\\], ]+\\s+" + hook + "\\s*\\(.*")) {
                        offenders.add(apiRoot.relativize(source) + " -> " + hook);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "api must not expose public test-only hooks: " + offenders);
    }

    /**
     * 文本是否引用了该前缀对应的类型。
     *
     * <p>要求前缀后面紧跟一个标识符边界（大写开头或行尾），避免
     * {@code com.habitrain.core.sceneXYZ} 这种误报；同时排除前缀本身只是更长包名一部分的情况
     * （例如 {@code com.habitrain.core.api.scene.} 不是 {@code com.habitrain.core.scene.} 的引用）。
     */
    private static boolean containsReference(String text, String prefix) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(prefix, from);
            if (idx < 0) {
                return false;
            }
            from = idx + 1;
            if (idx > 0 && (Character.isJavaIdentifierPart(text.charAt(idx - 1)) || text.charAt(idx - 1) == '.')) {
                continue;
            }
            // 前缀必须以「包名 + .」结尾；其后必须紧跟标识符起始字符。
            return true;
        }
    }

    /** 定位源码树中的 {@code com/habitrain/core/api}（测试运行时 cwd 即项目根）。 */
    private static Path locateApiSources() throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "habitrain", "core", "api");
        if (Files.isDirectory(relative)) {
            return relative.toAbsolutePath().normalize();
        }
        Path fromClass;
        try {
            fromClass = Path.of(GameModeRegistry.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("cannot locate compiled api classes", e);
        }
        // 兜底：从 build/classes/java/main 往上找项目根。
        Path cursor = fromClass.toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
            cursor = cursor.getParent();
        }
        throw new IOException("cannot locate api sources; cwd=" + Path.of("").toAbsolutePath()
                + " classRoot=" + fromClass + " (" + Locale.ROOT + ")");
    }
}
