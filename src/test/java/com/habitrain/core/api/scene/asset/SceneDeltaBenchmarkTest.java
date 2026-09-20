package com.habitrain.core.api.scene.asset;


import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量补丁评测的入口。
 *
 * <p>真实语料模式由 {@code HABITRAIN_SCENE_SAMPLES} 环境变量打开；没有这个环境变量就跳过——
 * 构建绝不能因为「某个外部目录不在」而失败。</p>
 */
class SceneDeltaBenchmarkTest {

    @Test
    void realCorpusDeltaSweep() throws IOException {
        String samples = System.getenv(SceneAssetBenchmark.SAMPLES_ENV);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                samples != null && !samples.isBlank() && Files.isDirectory(Path.of(samples)),
                "未提供 " + SceneAssetBenchmark.SAMPLES_ENV + "，跳过真实语料评测");

        SceneDeltaBenchmark.Report report = SceneDeltaBenchmark.run(Path.of(samples));
        String markdown = report.toMarkdown();
        System.out.println(markdown);

        Path out = Path.of("build", "reports", "scene-benchmark", "delta.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, markdown);

        assertFalse(report.rows().isEmpty(), "语料目录里应当至少有一个可解码的资产");
        // 100% 改动时补丁不应比整份还大太多：超过就说明补丁容器本身有膨胀问题。
        for (SceneDeltaBenchmark.Row row : report.rows()) {
            if (row.changeRatio() >= 1.0 && row.patchBytes() > 0) {
                assertTrue(row.patchBytes() < row.fullBytes() * 2,
                        "整份都变了的时候补丁不该膨胀到两倍以上 " + row);
            }
        }
    }

    /**
     * 无外部语料也能跑的合成护栏：改动越少补丁越小，且小改动一定落在会提供增益的区间。
     */
    @Test
    void smallChangeStaysCheapOnSyntheticCorpus() throws IOException {
        Path dir = Files.createTempDirectory("habitrain-delta-corpus");
        try {
            // 12 个 Section 的合成资产：真实形状的简化版，够验证「补丁随改动单调增长」。
            java.util.List<SceneAssetCodec.SectionData> sections = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                short[] indices = new short[4096];
                for (int cell = 0; cell < indices.length; cell++) {
                    indices[cell] = (short) ((cell % 5 == 0) ? 0 : 1 + (cell % 3));
                }
                sections.add(new SceneAssetCodec.SectionData(i, 0, 0,
                        java.util.List.of("minecraft:air", "minecraft:stone",
                                "minecraft:dirt", "minecraft:oak_planks"),
                        indices, new byte[2048], new byte[2048]));
            }
            SceneAssetCodec.AssetData asset = new SceneAssetCodec.AssetData(
                    SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld",
                    new com.habitrain.core.api.scene.model.SceneBounds(0, 0, 0, 192, 16, 16),
                    new com.habitrain.core.api.scene.model.SceneBounds(0, 0, 0, 192, 16, 16),
                    "fp", sections, java.util.List.of());
            Files.write(dir.resolve("synthetic.hscene"),
                    SceneAssetCodec.encode(asset, SceneAssetCodec.FORMAT_VERSION_V3));

            SceneDeltaBenchmark.Report report = SceneDeltaBenchmark.run(dir);
            assertFalse(report.rows().isEmpty());

            for (SceneDeltaBenchmark.Row row : report.rows()) {
                assertTrue(row.patchBytes() > 0, "每个改动档都应当能构造出补丁: " + row);
                if (row.changeRatio() <= 0.05) {
                    assertTrue(row.patchBytes() < row.fullBytes(),
                            "5% 以内时补丁必须小于整份 " + row);
                }
            }
            long onePercent = report.rows().stream()
                    .filter(r -> r.changeRatio() == 0.01).mapToLong(SceneDeltaBenchmark.Row::patchBytes)
                    .findFirst().orElse(-1);
            long allChanged = report.rows().stream()
                    .filter(r -> r.changeRatio() == 1.0).mapToLong(SceneDeltaBenchmark.Row::patchBytes)
                    .findFirst().orElse(-1);
            assertTrue(onePercent < allChanged, "改动越多补丁必须越大: " + onePercent + " vs " + allChanged);
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响结果。
                    }
                });
            }
        }
    }
}
