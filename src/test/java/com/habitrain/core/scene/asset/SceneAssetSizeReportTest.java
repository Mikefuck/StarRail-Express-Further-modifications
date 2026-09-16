package com.habitrain.core.scene.asset;

import com.habitrain.core.scene.model.SceneBounds;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 体积归因必须与真实字节一致——否则日志里那句"都花在哪了"就是编的。
 */
public class SceneAssetSizeReportTest {

    private static SceneAssetCodec.AssetData asset(int paletteSize, int nonZeroBlocks, int sections) {
        List<String> palette = new ArrayList<>();
        palette.add(SceneAssetCodec.AIR_BLOCK_ID);
        for (int i = 1; i < paletteSize; i++) {
            palette.add("minecraft:block_" + i + "[a=1,b=2]");
        }
        List<SceneAssetCodec.SectionData> list = new ArrayList<>();
        for (int s = 0; s < sections; s++) {
            short[] indices = new short[4096];
            for (int i = 0; i < nonZeroBlocks && i < 4096; i++) {
                indices[i] = 1;
            }
            list.add(new SceneAssetCodec.SectionData(s % 32, 0, s / 32, palette, indices,
                    new byte[2048], new byte[2048]));
        }
        SceneBounds bounds = new SceneBounds(0, 0, 0, 512, 16, 32);
        return new SceneAssetCodec.AssetData(SceneAssetCodec.FORMAT_VERSION_V3, 3955,
                "minecraft:overworld", bounds, bounds.inflate(1), "fp", list, List.of());
    }

    private static long inflatedLength(byte[] encoded) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            return in.readAllBytes().length;
        }
    }

    @Test
    public void attributionSumEqualsTheRealInflatedLength() throws IOException {
        // 两档形状都要对：少量方块（走位图）与满方块（走打包）。
        assertAttributionMatches(asset(17, 3, 4));
        assertAttributionMatches(asset(17, 4096, 4));
        assertAttributionMatches(asset(257, 900, 6));
        assertAttributionMatches(asset(2, 2000, 2));
    }

    private static void assertAttributionMatches(SceneAssetCodec.AssetData data) throws IOException {
        byte[] encoded = SceneAssetCodec.encode(data);
        long real = inflatedLength(encoded);
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(data, 0L, encoded.length);
        assertEquals(real, report.rawTotalBytes(),
                "分项之和应等于真实解压长度：sections=" + data.sections.size());
    }

    @Test
    public void countsSparseAndPackedSectionsSeparately() {
        // 3 个方块 → 位图更小；4096 个方块 → 打包更小。
        SceneAssetSizeReport sparse = SceneAssetSizeReport.analyze(asset(17, 3, 2), 6L, 100L);
        SceneAssetSizeReport packed = SceneAssetSizeReport.analyze(asset(17, 4096, 2), 8192L, 100L);

        assertEquals(2, sparse.sparseSections());
        assertEquals(0, sparse.packedSections());
        assertEquals(0, packed.sparseSections());
        assertEquals(2, packed.packedSections());
    }

    @Test
    public void doesNotWarnOnAssetsLikeTheRealOnes() {
        // 真实语料里最大的一份：65 KB 压缩、64 个 Section、约 2.3 万非空气方块。
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(asset(17, 500, 64), 23_450L, 65_458L);

        assertTrue(report.warning().isEmpty(), "不该对真实量级的资产告警");
    }

    @Test
    public void warnsWhenCompressedSizeIsLarge() {
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(asset(257, 900, 64),
                20_000L, SceneAssetSizeReport.WARN_COMPRESSED_BYTES);

        Optional<SceneAssetSizeReport.Warning> warning = report.warning();
        assertTrue(warning.isPresent());
        assertEquals("COMPRESSED_SIZE", warning.get().code());
        assertFalse(warning.get().hint().isBlank());
    }

    @Test
    public void warnsOnSectionCountBeforeBlockDensity() {
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(
                asset(17, 10, SceneAssetSizeReport.WARN_SECTIONS), 100L, 1024L);

        assertEquals("SECTION_COUNT", report.warning().orElseThrow().code());
    }

    @Test
    public void warnsOnBlockDensity() {
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(
                asset(17, 10, 4), SceneAssetSizeReport.WARN_NON_AIR_BLOCKS, 1024L);

        assertEquals("NON_AIR_DENSITY", report.warning().orElseThrow().code());
    }

    @Test
    public void dominantPartIsTheLargestBucketAndDrivesTheHint() {
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(asset(257, 1000, 8), 8000L, 200_000L);

        // 光照每 section 固定 4096 字节；8 个 section 就是 32 KiB，调色板与索引都远小于它。
        assertEquals(SceneAssetSizeReport.Part.LIGHT, report.dominantPart());
        assertTrue(report.partShare(SceneAssetSizeReport.Part.LIGHT) > 50.0);
        assertEquals(1024L * 1024L, SceneAssetSizeReport.WARN_COMPRESSED_BYTES);
    }

    @Test
    public void humanBytesAndRatioHandleEdgeCases() {
        assertEquals("512 B", SceneAssetSizeReport.humanBytes(512L));
        assertEquals("1.0 KiB", SceneAssetSizeReport.humanBytes(1024L));
        assertEquals("1.0 MiB", SceneAssetSizeReport.humanBytes(1024L * 1024L));

        SceneAssetSizeReport zero = SceneAssetSizeReport.analyze(asset(17, 1, 1), 0L, 0L);
        assertEquals(0.0, zero.compressionRatio(), "压缩为 0 时不能除零");
        assertNotNull(zero.toLogLine());
    }

    @Test
    public void globalPaletteIsCountedOnceAcrossSections() {
        // 同一个调色板被 8 个 section 共用，全局表只应记 17 项。
        SceneAssetSizeReport report = SceneAssetSizeReport.analyze(asset(17, 100, 8), 800L, 4096L);

        assertEquals(17, report.globalPaletteEntries());
    }
}
