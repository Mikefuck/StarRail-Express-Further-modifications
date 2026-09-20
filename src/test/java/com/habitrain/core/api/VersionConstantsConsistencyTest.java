package com.habitrain.core.api;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本常量一致性守卫（审核 A-14 / D-01）。
 *
 * <p>2.0.11 及以前，仓库里有<b>四处</b>彼此独立、零测试约束的版本声明：
 * <ol>
 *   <li>{@link CoreApi#API_VERSION}（公开 API 语义版本）</li>
 *   <li>{@link CoreApi#ROLE_API_VERSION} 与 {@link RoleExtensionApi#apiVersion()}</li>
 *   <li>{@code src/main/resources/fabric.mod.json} 的 {@code custom} 块
 *       （{@code habitrain_core:api_version} / {@code habitrain_core:role_api}）</li>
 *   <li>{@code gradle.properties} 的 {@code mod_version}（产物版本）</li>
 * </ol>
 * 任何一处漂移都会让下游的能力判断失效，而在此之前没有任何测试会发现。
 *
 * <p><b>D-01 的澄清</b>：{@code mod_version}（产物版本，每次修复递增）与
 * {@code api_version}（公开 API 语义版本，只在契约变化时递增）是<b>两个不同概念</b>，
 * 因此本测试<b>不</b>要求它们相等——只要求二者都存在、格式合法，
 * 且 {@code api_version} 与代码里的常量一致。下游判断能力必须用
 * {@link CoreApi#apiVersion()} / {@link CoreApi#supports(String)}，而不是 {@code mod_version}。
 */
class VersionConstantsConsistencyTest {

    @Test
    void codeConstantsMatchManifestCustomBlock() throws Exception {
        String manifest = readProjectFile("src/main/resources/fabric.mod.json");

        assertEquals(CoreApi.API_VERSION, jsonString(manifest, "habitrain_core:api_version"),
                "fabric.mod.json custom.habitrain_core:api_version must equal CoreApi.API_VERSION (audit A-14)");
        assertEquals(CoreApi.ROLE_API_VERSION, jsonString(manifest, "habitrain_core:role_api"),
                "fabric.mod.json custom.habitrain_core:role_api must equal CoreApi.ROLE_API_VERSION (audit A-14)");
    }

    @Test
    void roleExtensionApiVersionMatchesCoreApiConstant() {
        RoleExtensionApi api = com.habitrain.core.api.spi.RoleSpi.extension();
        assertNotNull(api, "RoleSpi must expose a role-extension service so its apiVersion() is checkable");
        assertEquals(CoreApi.ROLE_API_VERSION, api.apiVersion(),
                "RoleExtensionApi.apiVersion() must equal CoreApi.ROLE_API_VERSION (audit A-14)");
    }

    @Test
    void apiVersionAndModVersionAreBothPresentAndDistinctConcepts() throws Exception {
        String gradleProperties = readProjectFile("gradle.properties");
        String modVersion = property(gradleProperties, "mod_version");

        // 公开 API 版本是 major.minor；产物版本是 major.minor.patch。
        assertTrue(CoreApi.API_VERSION.matches("\\d+\\.\\d+"),
                "CoreApi.API_VERSION must be a major.minor semantic API version, was: " + CoreApi.API_VERSION);
        assertTrue(CoreApi.ROLE_API_VERSION.matches("\\d+\\.\\d+"),
                "CoreApi.ROLE_API_VERSION must be a major.minor semantic API version, was: "
                        + CoreApi.ROLE_API_VERSION);

        // 两个版本号各自独立计数（见 AGENTS.md「版本更新规则」），因此这里<b>不</b>比较大小：
        // api 2.2 与 mod 2.0.12 同时存在是正常的——API 版本按契约递增，产物版本按构建递增，
        // 二者没有序关系（例如 api 2.2 / mod 2.0.13、api 2.10 / mod 2.1.0 都可能出现）。
        // 本测试只保证：两个版本号都存在、格式合法，且 api_version 与代码常量一致
        // （后者由 codeConstantsMatchManifestCustomBlock 保证）。
        assertTrue(modVersion.matches("\\d+\\.\\d+\\.\\d+"),
                "mod_version must be major.minor.patch, was: " + modVersion);
        assertEquals(3, modVersion.split("\\.").length,
                "mod_version must use the project's 3-part scheme (AGENTS.md 版本更新规则)");
    }

    /**
     * 审核 A-14 的另一半：{@link CoreApi} javadoc 必须给出老核心的兼容探针配方。
     *
     * <p>老核心（≤ 2.0.10）<b>没有</b> {@code CoreApi} 类，下游直接引用会
     * {@code NoClassDefFoundError}。没有可复制的探针配方时，下游只能用
     * {@code catch (Throwable)} 兜底并静默降级——这正是审核 B-06 / B-01 的成因。
     */
    @Test
    void coreApiJavadocDocumentsTheCompatibilityProbe() throws Exception {
        String source = readProjectFile(
                "src/main/java/com/habitrain/core/api/CoreApi.java");
        assertTrue(source.contains("Class.forName"),
                "CoreApi javadoc must show the Class.forName compatibility probe for older cores (audit A-14)");
        assertFalse(source.contains("com.habitrain.core.internal."),
                "CoreApi must not reference internal layers");
    }

    // ==================== helpers ====================

    private static String readProjectFile(String relative) throws Exception {
        Path path = Path.of(relative);
        if (!Files.isRegularFile(path)) {
            // Gradle 的测试工作目录已做 staging 处理，回退到从类位置向上找项目根。
            Path cursor = Path.of(CoreApi.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            for (int i = 0; i < 8 && cursor != null; i++) {
                Path candidate = cursor.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                }
                cursor = cursor.getParent();
            }
            throw new IllegalStateException("cannot locate " + relative);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String property(String properties, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(.+)$").matcher(properties);
        assertTrue(m.find(), "gradle.properties must define " + key);
        return m.group(1).trim();
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), "fabric.mod.json must declare \"" + key + "\"");
        return m.group(1);
    }
}
