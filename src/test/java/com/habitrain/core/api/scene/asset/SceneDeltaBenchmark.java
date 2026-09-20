package com.habitrain.core.api.scene.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 增量补丁的评测装置（只在测试源集里，不进成品 jar）。
 *
 * <p>它回答的问题是报告 §7 E 行留下的那个："Section 增量到底值不值得做、从多大开始划算"。
 * 做法是**拿真实资产当底、按比例扰动 Section 模拟一次重捕获**，再用生产编码器与生产补丁
 * 编码器分别算出"整份"与"补丁"的字节数。用真实形状而不是玩具样本，是因为 D 期已经吃过
 * 一次亏：微缩样本上的结论与真实语料相反。</p>
 *
 * <p>语料目录由环境变量 {@code HABITRAIN_SCENE_SAMPLES} 给出；没有就跳过（{@code gradlew build}
 * 永远不因外部目录缺失而红）。注意 Gradle 测试进程继承的是守护进程的环境，改环境变量前先
 * {@code gradlew --stop}。</p>
 */
public final class SceneDeltaBenchmark {

    /** 模拟重捕获时改动的 Section 占比。 */
    private static final double[] CHANGE_RATIOS = {0.01, 0.05, 0.20, 0.50, 1.00};

    private SceneDeltaBenchmark() {}

    public record Row(String name, int sections, long fullBytes, double changeRatio, long patchBytes) {
        public double patchPercent() {
            return fullBytes <= 0 ? 0.0 : 100.0 * patchBytes / fullBytes;
        }
    }

    public record Report(List<Row> rows, List<String> skipped, String corpusDescription) {
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# 场景增量补丁评测\n\n");
            sb.append("语料：").append(corpusDescription).append("\n\n");
            sb.append("做法：把真实资产按 v3 重新编码得到\"整份\"基线，再按占比扰动它的 Section 模拟一次")
                    .append("重捕获，用生产补丁编码器算出补丁字节。**补丁必须小于整份的 ")
                    .append((int) (SceneDeltaBuilderRatio() * 100))
                    .append("% 才会被服务端提供**（见 `SceneDeltaBuilder.MAX_PATCH_SIZE_RATIO`）。\n\n");
            sb.append("| 资产 | Section | 整份字节 | 改动占比 | 补丁字节 | 补丁/整份 | 会提供增量 |\n");
            sb.append("|---|---:|---:|---:|---:|---:|:--:|\n");
            for (Row row : rows) {
                sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %.0f%% | %d | %.1f%% | %s |%n",
                        row.name(), row.sections(), row.fullBytes(), row.changeRatio() * 100,
                        row.patchBytes(), row.patchPercent(),
                        row.patchBytes() < row.fullBytes() * SceneDeltaBuilderRatio() ? "是" : "否"));
            }
            if (!skipped.isEmpty()) {
                sb.append("\n跳过：").append(String.join("、", skipped)).append('\n');
            }
            return sb.toString();
        }
    }

    /** 与服务端一致的"补丁够不够小"阈值；这里复述常量以免测试源集依赖服务端类。 */
    private static double SceneDeltaBuilderRatio() {
        return 0.8;
    }

    /** 读取语料目录里的 {@code .hscene}；解码失败的记进 skipped。 */
    public static Report run(Path directory) throws IOException {
        List<Row> rows = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".hscene")).sorted().forEach(files::add);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            SceneAssetCodec.AssetData base;
            try {
                base = SceneAssetCodec.decode(Files.readAllBytes(file));
            } catch (Exception e) {
                skipped.add(name.substring(0, 12) + "（解码失败）");
                continue;
            }
            if (base.sections.isEmpty()) {
                skipped.add(name.substring(0, 12) + "（空资产）");
                continue;
            }
            try {
                byte[] fullBytes = SceneAssetCodec.encode(base, SceneAssetCodec.FORMAT_VERSION_V3);
                String baseHash = SceneAssetCodec.calculateSha256(fullBytes);
                for (double ratio : CHANGE_RATIOS) {
                    SceneAssetCodec.AssetData perturbed = perturb(base, ratio);
                    byte[] targetBytes = SceneAssetCodec.encode(perturbed, SceneAssetCodec.FORMAT_VERSION_V3);
                    String targetHash = SceneAssetCodec.calculateSha256(targetBytes);
                    SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, baseHash, perturbed, targetHash);
                    long patchBytes = delta == null ? -1L : SceneAssetDelta.encode(delta).length;
                    rows.add(new Row(name.substring(0, 12), base.sections.size(), fullBytes.length,
                            ratio, patchBytes));
                }
            } catch (Exception e) {
                skipped.add(name.substring(0, 12) + "（" + e.getClass().getSimpleName() + "）");
            }
        }
        return new Report(rows, skipped, directory.toString());
    }

    /**
     * 按比例扰动若干个 Section：每个被选中的 Section 里每 10 格换一次方块。
     *
     * <p>这模拟的是"重新捕获时有一小片区域变了"——真正的重捕获还会改变 Section 的增删，
     * 那属于更大范围的改动，由 100% 一档覆盖。</p>
     */
    private static SceneAssetCodec.AssetData perturb(SceneAssetCodec.AssetData source, double ratio) {
        int total = source.sections.size();
        int changed = (int) Math.max(1, Math.round(total * ratio));
        List<SceneAssetCodec.SectionData> sections = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            SceneAssetCodec.SectionData section = source.sections.get(i);
            if (i >= changed) {
                sections.add(section);
                continue;
            }
            short[] indices = section.blockIndices.clone();
            int paletteSize = Math.max(1, section.palette.size());
            for (int cell = 0; cell < indices.length; cell += 10) {
                indices[cell] = (short) ((indices[cell] + 1) % paletteSize);
            }
            byte[] sky = section.skyLight.clone();
            for (int cell = 0; cell < sky.length; cell += 10) {
                sky[cell] = (byte) (sky[cell] ^ 0x1);
            }
            sections.add(new SceneAssetCodec.SectionData(section.relX, section.relY, section.relZ,
                    section.palette, indices, sky, section.blockLight.clone()));
        }
        return new SceneAssetCodec.AssetData(SceneAssetCodec.FORMAT_VERSION_V3, source.dataVersion,
                source.dimensionId, source.sourceBounds, source.haloBounds, source.fingerprint,
                sections, List.of());
    }
}
