package com.habitrain.core.scene.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 紧凑编码的评测驱动。
 *
 * <p>合成语料每次都跑（快、确定性），它守的是"v3 不能比 v2 更差"和"v3 不改变语义"这两条底线。
 * 真实语料要显式给目录，没给就跳过——绝不能让 {@code gradlew build} 因为外部目录不存在而变红。</p>
 *
 * <p>跑真实语料：{@code bash gradlew --stop} 之后
 * {@code HABITRAIN_SCENE_SAMPLES="D:/.../habitrain_scene_assets" bash gradlew test --tests "*SceneAssetBenchmarkTest*"}。
 * 必须先停守护进程：测试进程继承的是守护进程的环境，旧守护进程看不到新设的变量。</p>
 */
public class SceneAssetBenchmarkTest {

    private static final Path REPORT_DIR = Path.of("build", "reports", "scene-benchmark");

    /** 合成语料上 v3 至少要赢这么多。实测远高于此，留足余量只当护栏。 */
    private static final double SYNTHETIC_MIN_SAVING_PERCENT = 8.0;

    @Test
    public void syntheticCorpusProvesV3IsSmallerAndLossless() throws IOException {
        SceneAssetBenchmark.Report report = SceneAssetBenchmark.runSyntheticCorpus();

        for (SceneAssetBenchmark.AssetResult r : report.results()) {
            assertNull(r.error(), "资产 " + r.name() + " 处理失败: " + r.error());
            assertTrue(r.modelEquivalent(), "v2/v3 解码模型必须一致: " + r.name());
        }
        assertTrue(report.savingPercent() >= SYNTHETIC_MIN_SAVING_PERCENT,
                "合成语料上 v3 至少应省 " + SYNTHETIC_MIN_SAVING_PERCENT + "%，实测 "
                        + String.format(java.util.Locale.ROOT, "%.2f%%", report.savingPercent()));

        writeReport("synthetic.md", report);
        System.out.println(SceneAssetBenchmark.toMarkdown(report));
    }

    @Test
    public void realCorpusReproducesTheV2BaselineAndConfirmsV3() throws IOException {
        String dir = System.getenv(SceneAssetBenchmark.SAMPLES_ENV);
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty(SceneAssetBenchmark.SAMPLES_ENV);
        }
        Assumptions.assumeTrue(dir != null && !dir.isBlank(),
                "未设置 " + SceneAssetBenchmark.SAMPLES_ENV + "，跳过真实语料评测");

        Path path = Path.of(dir);
        Assumptions.assumeTrue(Files.isDirectory(path), "目录不存在: " + path);

        SceneAssetBenchmark.Report report = SceneAssetBenchmark.runRealCorpus(path);
        // 先把报告落盘再断言：断言失败时更需要这份逐资产证据。
        writeReport("real-corpus.md", report);
        System.out.println(SceneAssetBenchmark.toMarkdown(report));

        assertFalse(report.results().isEmpty(), "目录里没有 .hscene: " + path);
        for (SceneAssetBenchmark.AssetResult r : report.results()) {
            assertNull(r.error(), "资产 " + r.name() + " 处理失败: " + r.error());
            assertTrue(r.modelEquivalent(), "v2/v3 解码模型必须一致: " + r.name());
            // 把解码出的模型按它自己的版本重新编码，应当与磁盘上的原文件逐字节相同：
            // 这证明解码器把每个字段都读准了，没有靠默认值兜底。
            assertTrue(r.baselineReproducedByteIdentically(),
                    "按 v" + r.baselineVersion() + " 重编码未能复现原文件: " + r.name());
            assertTrue(r.v3Bytes() < r.baselineBytes(), "v3 应小于现有格式: " + r.name());
        }
    }

    private static void writeReport(String fileName, SceneAssetBenchmark.Report report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve(fileName),
                SceneAssetBenchmark.toMarkdown(report), StandardCharsets.UTF_8);
    }
}
