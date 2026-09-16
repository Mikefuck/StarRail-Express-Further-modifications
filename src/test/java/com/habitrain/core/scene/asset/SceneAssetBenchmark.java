package com.habitrain.core.scene.asset;

import com.habitrain.core.scene.model.SceneBounds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * v3 紧凑编码的评测装置（只在测试源集里，不进成品 jar）。
 *
 * <p>它刻意<b>调用生产编码器</b>而不是另写一套模型：候选布局就是
 * {@code SceneAssetCodec.encode(data, FORMAT_VERSION_V2/V3, level)} 本身，所以"评测出来的
 * 收益"与"线上会拿到的收益"是同一份实现，不存在报告 §8 警告的"拿不同实现比大小"。</p>
 *
 * <p>真实语料从环境变量 {@code HABITRAIN_SCENE_SAMPLES} 指向的目录读 {@code *.hscene}。
 * 注意 Gradle 的测试进程继承的是<b>守护进程</b>的环境，新设的变量要先把守护进程停掉
 * （{@code bash gradlew --stop}）才会生效。</p>
 */
public final class SceneAssetBenchmark {

    /** 真实语料目录的环境变量名。 */
    public static final String SAMPLES_ENV = "HABITRAIN_SCENE_SAMPLES";

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 3;

    private SceneAssetBenchmark() {}

    // ---- 结果模型 ----

    public record AssetResult(
            String name,
            long originalBytes,
            int sectionCount,
            long nonAirBlocks,
            int baselineVersion,
            long baselineBytes,
            long v3Bytes,
            long v3BytesLevel9,
            long rawBaselineBytes,
            long rawV3Bytes,
            double baselineEncodeMs,
            double v3EncodeMs,
            double baselineDecodeMs,
            double v3DecodeMs,
            boolean modelEquivalent,
            boolean baselineReproducedByteIdentically,
            String error) {

        public boolean ok() {
            return error == null;
        }

        public double v3SavingPercent() {
            if (baselineBytes <= 0) return 0.0;
            return 100.0 * (baselineBytes - v3Bytes) / baselineBytes;
        }
    }

    public record Report(List<AssetResult> results, String corpusDescription) {

        public long totalOriginal() {
            return results.stream().filter(AssetResult::ok).mapToLong(AssetResult::originalBytes).sum();
        }

        public long totalBaseline() {
            return results.stream().filter(AssetResult::ok).mapToLong(AssetResult::baselineBytes).sum();
        }

        public long totalV3() {
            return results.stream().filter(AssetResult::ok).mapToLong(AssetResult::v3Bytes).sum();
        }

        public long totalV3Level9() {
            return results.stream().filter(AssetResult::ok).mapToLong(AssetResult::v3BytesLevel9).sum();
        }

        public int failures() {
            return (int) results.stream().filter(r -> !r.ok()).count();
        }

        public double savingPercent() {
            long baseline = totalBaseline();
            if (baseline <= 0) return 0.0;
            return 100.0 * (baseline - totalV3()) / baseline;
        }
    }

    // ---- 语料 ----

    /**
     * 三档合成语料，覆盖 v3 的两条分支与两个极端形状。
     *
     * <p>刻意做得比"能跑就行"更接近真实捕获：调色板里是真实的方块串（含带状态的方块），
     * 空气占比按真实资产的量级给（85%–99%），而不是均匀随机。</p>
     */
    public static List<SceneAssetCodec.AssetData> syntheticCorpus() {
        List<SceneAssetCodec.AssetData> corpus = new ArrayList<>();
        corpus.add(syntheticAsset("小型普通方块", 8, 6, 0.80, true, 1L));
        corpus.add(syntheticAsset("稀疏大区域", 48, 10, 0.97, true, 2L));
        corpus.add(syntheticAsset("密集多材质", 24, 64, 0.05, false, 3L));
        return corpus;
    }

    private static final String[] BLOCK_IDS = {
            "minecraft:stone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:glass",
            "minecraft:water", "minecraft:oak_slab[type=top,waterlogged=false]",
            "minecraft:oak_fence[east=true,north=false,south=true,waterlogged=false,west=false]",
            "minecraft:white_wool", "minecraft:stone_bricks", "minecraft:cobblestone_wall[north=low]",
            "minecraft:lantern[hanging=true,waterlogged=false]", "minecraft:dirt_path",
    };

    private static SceneAssetCodec.AssetData syntheticAsset(String name, int sectionCount,
                                                            int paletteSize, double airFraction,
                                                            boolean uniformLight, long seed) {
        Random random = new Random(seed);
        List<String> palette = new ArrayList<>();
        palette.add(SceneAssetCodec.AIR_BLOCK_ID); // 捕获路径的约定：第 0 位永远是空气
        for (int i = 1; i < paletteSize; i++) {
            if (i - 1 < BLOCK_IDS.length) {
                palette.add(BLOCK_IDS[i - 1]);
            } else {
                palette.add("mod:decor_block_" + i + "[facing=north,lit=true]");
            }
        }

        List<SceneAssetCodec.SectionData> sections = new ArrayList<>();
        for (int s = 0; s < sectionCount; s++) {
            short[] indices = new short[4096];
            for (int i = 0; i < 4096; i++) {
                indices[i] = (short) (random.nextDouble() < airFraction ? 0
                        : 1 + random.nextInt(Math.max(1, paletteSize - 1)));
            }
            byte[] sky = new byte[2048];
            byte[] block = new byte[2048];
            if (uniformLight) {
                java.util.Arrays.fill(sky, (byte) 0xFF);
            } else {
                for (int i = 0; i < sky.length; i++) {
                    sky[i] = (byte) (random.nextInt(16) * 17);
                    block[i] = (byte) (random.nextInt(8) * 17);
                }
            }
            sections.add(new SceneAssetCodec.SectionData(s % 32, 0, s / 32, palette, indices, sky, block));
        }

        // 单轴上限 512 格 = 32 个 section，所以要按 32 个一行铺开，不能一条直线排下去。
        int spanX = Math.min(sectionCount, 32) * 16;
        int spanZ = ((sectionCount + 31) / 32) * 16;
        SceneBounds bounds = new SceneBounds(0, 0, 0, Math.max(16, spanX), 16, Math.max(16, spanZ));
        // 模型自带 v2：合成的这批要扮演"线上已有的资产"，对照基线才是 v2，v3 才是被评的那一方。
        return new SceneAssetCodec.AssetData(SceneAssetCodec.FORMAT_VERSION_V2, 3955,
                "minecraft:overworld", bounds, bounds.inflate(1), "benchmark_fingerprint", sections, List.of());
    }

    /** 读取目录下所有 {@code *.hscene}，逐个解码成内存模型。单个文件坏掉只记一行，不中断整轮。 */
    public static List<AssetResult> loadDirectory(Path dir) {
        List<AssetResult> loaded = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return loaded;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".hscene")).sorted().forEach(files::add);
        } catch (IOException e) {
            return loaded;
        }
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                SceneAssetCodec.AssetData data = SceneAssetCodec.decode(bytes);
                loaded.add(measure(file.getFileName().toString(), bytes, data));
            } catch (Throwable t) {
                loaded.add(failed(file.getFileName().toString(), t));
            }
        }
        return loaded;
    }

    public static Report runRealCorpus(Path dir) {
        List<AssetResult> results = loadDirectory(dir);
        return new Report(results, "真实语料: " + dir + " (" + results.size() + " 个资产)");
    }

    public static Report runSyntheticCorpus() {
        List<AssetResult> results = new ArrayList<>();
        List<SceneAssetCodec.AssetData> assets = syntheticCorpus();
        for (int i = 0; i < assets.size(); i++) {
            SceneAssetCodec.AssetData data = assets.get(i);
            String name = List.of("小型普通方块", "稀疏大区域", "密集多材质").get(i);
            try {
                results.add(measure(name, null, data));
            } catch (Throwable t) {
                results.add(failed(name, t));
            }
        }
        return new Report(results, "合成语料 (3 档形状)");
    }

    private static AssetResult failed(String name, Throwable t) {
        return new AssetResult(name, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    // ---- 单个资产的测量 ----

    private static AssetResult measure(String name, byte[] originalBytes,
                                       SceneAssetCodec.AssetData model) throws IOException {
        long originalLength = originalBytes != null ? originalBytes.length : 0;
        // 对照基线用"这份模型自己的版本"，也就是服务端今天会给出的字节：
        // 语料里 v1 与 v2 混存（连最大的一张地图都还是 v1），拿 v2 当基线会让
        // "重编码是否复现原文件"这条自证在 v1 资产上假失败——v2 会多写 halo 与载荷计数。
        int baselineVersion = model.formatVersion > 0 ? model.formatVersion : SceneAssetCodec.FORMAT_VERSION;

        long encodeStart = System.nanoTime();
        byte[] baseline = timeEncode(model, baselineVersion, SceneAssetCodec.DEFAULT_GZIP_LEVEL);
        double baselineEncodeMs = millisSince(encodeStart);

        encodeStart = System.nanoTime();
        byte[] v3 = timeEncode(model, SceneAssetCodec.FORMAT_VERSION_V3, SceneAssetCodec.DEFAULT_GZIP_LEVEL);
        double v3EncodeMs = millisSince(encodeStart);

        byte[] v3Level9 = timeEncode(model, SceneAssetCodec.FORMAT_VERSION_V3, 9);

        long decodeStart = System.nanoTime();
        SceneAssetCodec.AssetData fromBaseline = SceneAssetCodec.decode(baseline);
        double baselineDecodeMs = millisSince(decodeStart);

        decodeStart = System.nanoTime();
        SceneAssetCodec.AssetData fromV3 = SceneAssetCodec.decode(v3);
        double v3DecodeMs = millisSince(decodeStart);

        boolean equivalent = sameModel(fromBaseline, fromV3);
        boolean reproduced = originalBytes != null && java.util.Arrays.equals(originalBytes, baseline);

        return new AssetResult(name, originalLength, model.sections.size(), countNonAir(model),
                baselineVersion, baseline.length, v3.length, v3Level9.length,
                SceneAssetCodec.estimateUncompressedSize(model, baselineVersion),
                SceneAssetCodec.estimateUncompressedSize(model, SceneAssetCodec.FORMAT_VERSION_V3),
                baselineEncodeMs, v3EncodeMs, baselineDecodeMs, v3DecodeMs, equivalent, reproduced, null);
    }

    private static byte[] timeEncode(SceneAssetCodec.AssetData model, int version, int level)
            throws IOException {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            SceneAssetCodec.encode(model, version, level);
        }
        byte[] last = null;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            last = SceneAssetCodec.encode(model, version, level);
        }
        return last;
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static long countNonAir(SceneAssetCodec.AssetData model) {
        long total = 0L;
        for (SceneAssetCodec.SectionData section : model.sections) {
            for (short value : section.blockIndices) {
                if (value != 0) total++;
            }
        }
        return total;
    }

    private static boolean sameModel(SceneAssetCodec.AssetData a, SceneAssetCodec.AssetData b) {
        if (a.sections.size() != b.sections.size()) return false;
        if (!a.dimensionId.equals(b.dimensionId) || !a.fingerprint.equals(b.fingerprint)) return false;
        if (!a.sourceBounds.equals(b.sourceBounds) || !a.haloBounds.equals(b.haloBounds)) return false;
        if (a.blockPayloads.size() != b.blockPayloads.size()) return false;
        for (int s = 0; s < a.sections.size(); s++) {
            SceneAssetCodec.SectionData sa = a.sections.get(s);
            SceneAssetCodec.SectionData sb = b.sections.get(s);
            if (sa.relX != sb.relX || sa.relY != sb.relY || sa.relZ != sb.relZ) return false;
            if (!sa.palette.equals(sb.palette)) return false;
            if (!java.util.Arrays.equals(sa.blockIndices, sb.blockIndices)) return false;
            if (!java.util.Arrays.equals(sa.skyLight, sb.skyLight)) return false;
            if (!java.util.Arrays.equals(sa.blockLight, sb.blockLight)) return false;
        }
        return true;
    }

    // ---- 报告 ----

    public static String toMarkdown(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 场景资产 v3 紧凑编码评测\n\n");
        sb.append("语料：").append(report.corpusDescription()).append("\n\n");
        sb.append("说明：v2 与 v3 都是生产编码器（`SceneAssetCodec.encode`）的输出，")
                .append("deflate 等级 6；等级 9 一列只是用来量\"更高压缩级别还值不值\"。\n\n");
        sb.append("| 资产 | 现有(vN) | v3 | 节省 | v3@9 | 原始 现有 | 原始 v3 | sections | 非空气 | 现有编码 ms | v3 编码 ms | 现有解码 ms | v3 解码 ms | 模型一致 | 复现 |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:--:|:--:|\n");
        for (AssetResult r : report.results()) {
            if (!r.ok()) {
                sb.append("| ").append(r.name()).append(" | — | — | — | — | — | — | — | — | — | — | — | — | ✗ | ✗ |\n");
                continue;
            }
            sb.append(String.format(Locale.ROOT,
                    "| %s | %d (v%d) | %d | %+.2f%% | %d | %d | %d | %d | %d | %.1f | %.1f | %.1f | %.1f | %s | %s |%n",
                    r.name(), r.baselineBytes(), r.baselineVersion(), r.v3Bytes(), -r.v3SavingPercent(),
                    r.v3BytesLevel9(), r.rawBaselineBytes(), r.rawV3Bytes(), r.sectionCount(), r.nonAirBlocks(),
                    r.baselineEncodeMs(), r.v3EncodeMs(), r.baselineDecodeMs(), r.v3DecodeMs(),
                    r.modelEquivalent() ? "✓" : "✗", r.baselineReproducedByteIdentically() ? "✓" : "—"));
        }
        sb.append(String.format(Locale.ROOT, "| **合计** | **%d** | **%d** | **%+.2f%%** | **%d** | | | | | | | | | | |%n",
                report.totalBaseline(), report.totalV3(), -report.savingPercent(), report.totalV3Level9()));
        sb.append("\nv3 相对现有格式节省 **")
                .append(String.format(Locale.ROOT, "%.2f%%", report.savingPercent()))
                .append("**（")
                .append(report.totalBaseline() - report.totalV3())
                .append(" 字节）");
        if (report.totalBaseline() > 0) {
            sb.append("；等级 9 再省 ").append(report.totalV3() - report.totalV3Level9()).append(" 字节");
        }
        sb.append("。\n\n");
        sb.append("- `现有(vN)`：按该资产**自己的**版本（v1 或 v2）重编码，也就是服务端今天会给出的字节。\n");
        sb.append("- `模型一致`：同一份模型分别按现有格式与 v3 编解码后逐字段相同（v3 只改编码、不改语义）。\n");
        sb.append("- `复现`：把解码出的模型按它自己的版本重编码，是否与磁盘上的原文件逐字节相同")
                .append("（相同即证明解码器把每个字段都读准了，没有默认值兜底）。\n");
        if (report.failures() > 0) {
            sb.append("\n有 ").append(report.failures()).append(" 个资产处理失败：\n\n");
            for (AssetResult r : report.results()) {
                if (!r.ok()) {
                    sb.append("- `").append(r.name()).append("`：").append(r.error()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
