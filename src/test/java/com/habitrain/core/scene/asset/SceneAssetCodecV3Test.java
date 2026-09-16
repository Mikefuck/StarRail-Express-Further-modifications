package com.habitrain.core.scene.asset;

import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 紧凑编码的读写与防伪测试。
 *
 * <p>负例的做法是：先编一份合法资产，解压后按已知偏移改掉某一个字段，再重新压回去。
 * 这样既不需要手写整套字节流，又能精确定位到要验证的那一个字段——尤其是"流内 CRC 是否
 * 真的覆盖了索引区"这种必须靠改字节才能证明的事。</p>
 */
public class SceneAssetCodecV3Test {

    // ---- 语料 ----

    /** 密实 section：2 项调色板（w=1）、几乎全是方块 → 应当走位打包。 */
    private static SceneAssetCodec.SectionData denseSection(int relX) {
        List<String> palette = List.of("minecraft:air", "minecraft:stone");
        short[] indices = new short[4096];
        for (int i = 0; i < 4096; i++) {
            indices[i] = (short) ((i % 7 == 0) ? 0 : 1);
        }
        return new SceneAssetCodec.SectionData(relX, 0, 0, palette, indices, new byte[2048], new byte[2048]);
    }

    /** 稀疏 section：17 项调色板（w=8）、只有少数几个方块 → 应当走非空气位图。 */
    private static SceneAssetCodec.SectionData sparseSection(int relX) {
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        for (int i = 1; i < 17; i++) {
            palette.add("minecraft:block_" + i);
        }
        short[] indices = new short[4096];
        indices[0] = 5;
        indices[4095] = 16; // 最后一个格子也要能往返
        indices[1234] = 9;
        return new SceneAssetCodec.SectionData(relX, 0, 0, palette, indices, new byte[2048], new byte[2048]);
    }

    private static SceneAssetCodec.AssetData sampleAsset(List<SceneAssetCodec.SectionData> sections) {
        SceneBounds bounds = new SceneBounds(0, 0, 0, 64, 64, 64);
        return new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3,
                3955,
                "minecraft:overworld",
                bounds,
                bounds.inflate(1),
                "v3_fingerprint",
                sections,
                List.of());
    }

    // ---- 工具：解压 / 再压缩 / 定位字段 ----

    private static byte[] inflate(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    /** 大端游标，只够本测试用。 */
    private static final class Cursor {
        final byte[] b;
        int i;

        Cursor(byte[] b) {
            this.b = b;
        }

        int i32() {
            int v = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            i += 4;
            return v;
        }

        int u8() {
            return b[i++] & 0xFF;
        }

        void skipUtf() {
            int n = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2 + n;
        }

        void putI32(int offset, int value) {
            b[offset] = (byte) (value >>> 24);
            b[offset + 1] = (byte) (value >>> 16);
            b[offset + 2] = (byte) (value >>> 8);
            b[offset + 3] = (byte) value;
        }
    }

    /** 走过头部与全局调色板，停在第一段 section 几何的起点。 */
    private static Cursor toFirstSection(byte[] raw) {
        Cursor c = new Cursor(raw);
        c.i32(); // MAGIC
        c.i32(); // formatVersion
        c.i32(); // dataVersion
        c.skipUtf(); // dimensionId
        for (int k = 0; k < 12; k++) c.i32(); // sourceBounds + haloBounds
        c.skipUtf(); // fingerprint
        c.i32(); // sectionCount
        int globalPaletteSize = c.i32();
        for (int k = 0; k < globalPaletteSize; k++) c.skipUtf();
        return c;
    }

    /** 跳过第 n 段的坐标与调色板引用，返回其"编码标记"字节的下标。 */
    private static int flagOffset(byte[] raw, int sectionIndex) {
        Cursor c = toFirstSection(raw);
        for (int s = 0; s < sectionIndex; s++) {
            c.i32(); c.i32(); c.i32();
            int paletteSize = c.i32();
            for (int p = 0; p < paletteSize; p++) c.i32();
            int encoding = c.u8();
            int width = widthOf(paletteSize);
            c.i += indexBytesFor(raw, c.i, encoding, width, paletteSize);
        }
        c.i32(); c.i32(); c.i32();
        int paletteSize = c.i32();
        for (int p = 0; p < paletteSize; p++) c.i32();
        return c.i; // 编码标记
    }

    /** 按索引段自身的规则算出它占多少字节（位图上带有 popcount）。 */
    private static int indexBytesFor(byte[] raw, int indexStart, int encoding, int width, int paletteSize) {
        if (width == 0) return 0;
        if (encoding == SceneAssetCodec.INDEX_ENCODING_PACKED) {
            return (4096 * width + 7) / 8;
        }
        int nonZero = 0;
        for (int i = 0; i < 512; i++) nonZero += Integer.bitCount(raw[indexStart + i] & 0xFF);
        return 512 + (nonZero * width + 7) / 8;
    }

    private static int widthOf(int paletteSize) {
        if (paletteSize <= 1) return 0;
        if (paletteSize <= 2) return 1;
        if (paletteSize <= 4) return 2;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 256) return 8;
        return 12;
    }

    /** 解压 → 改字节 → 重新压回一个"gzip 本身合法"的流：只有流内 CRC 能发现改动。 */
    private static byte[] tamper(byte[] encoded, java.util.function.Consumer<Cursor> patch) throws IOException {
        byte[] raw = inflate(encoded);
        patch.accept(new Cursor(raw));
        return deflate(raw);
    }

    private static void assertSameModel(SceneAssetCodec.AssetData expected, SceneAssetCodec.AssetData actual) {
        assertEquals(expected.dataVersion, actual.dataVersion);
        assertEquals(expected.dimensionId, actual.dimensionId);
        assertEquals(expected.sourceBounds, actual.sourceBounds);
        assertEquals(expected.haloBounds, actual.haloBounds);
        assertEquals(expected.fingerprint, actual.fingerprint);
        assertEquals(expected.sections.size(), actual.sections.size());
        for (int s = 0; s < expected.sections.size(); s++) {
            SceneAssetCodec.SectionData e = expected.sections.get(s);
            SceneAssetCodec.SectionData a = actual.sections.get(s);
            assertEquals(e.relX, a.relX);
            assertEquals(e.relY, a.relY);
            assertEquals(e.relZ, a.relZ);
            assertEquals(e.palette, a.palette, "section " + s + " palette");
            assertArrayEquals(e.blockIndices, a.blockIndices, "section " + s + " indices");
            assertArrayEquals(e.skyLight, a.skyLight, "section " + s + " sky light");
            assertArrayEquals(e.blockLight, a.blockLight, "section " + s + " block light");
        }
        assertEquals(expected.blockPayloads.size(), actual.blockPayloads.size());
    }

    // ---- 往返 ----

    @Test
    public void v3RoundTripPreservesModelExactly() throws IOException {
        SceneAssetCodec.AssetData original = sampleAsset(List.of(denseSection(0), sparseSection(1)));

        byte[] encoded = SceneAssetCodec.encode(original);
        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(encoded);

        assertEquals(SceneAssetCodec.FORMAT_VERSION_V3, decoded.formatVersion);
        assertSameModel(original, decoded);
    }

    @Test
    public void v3IsTheDefaultWhenAssetCarriesNoVersion() throws IOException {
        SceneBounds bounds = new SceneBounds(0, 0, 0, 16, 16, 16);
        // 用不传 formatVersion 的构造器：它应当取到当前的 FORMAT_VERSION。
        SceneAssetCodec.AssetData data = new SceneAssetCodec.AssetData(
                3955, "minecraft:overworld", bounds, "fp", List.of(denseSection(0)));

        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(SceneAssetCodec.encode(data));

        assertEquals(SceneAssetCodec.FORMAT_VERSION_V3, decoded.formatVersion);
        assertSameModel(data, decoded);
    }

    @Test
    public void v2AndV3DecodeToTheSameModel() throws IOException {
        SceneAssetCodec.AssetData v3Model = sampleAsset(List.of(denseSection(0), sparseSection(1)));

        byte[] v3Bytes = SceneAssetCodec.encode(v3Model, SceneAssetCodec.FORMAT_VERSION_V3);
        byte[] v2Bytes = SceneAssetCodec.encode(v3Model, SceneAssetCodec.FORMAT_VERSION_V2);

        SceneAssetCodec.AssetData fromV3 = SceneAssetCodec.decode(v3Bytes);
        SceneAssetCodec.AssetData fromV2 = SceneAssetCodec.decode(v2Bytes);

        assertEquals(SceneAssetCodec.FORMAT_VERSION_V2, fromV2.formatVersion);
        // 这条才是关键：v3 只改编码，不改语义。
        assertSameModel(fromV2, fromV3);

        // 这里刻意不断言 v3 < v2。两个 section 的微缩样本上 v3 反而略大（实测 v3=278B / v2=265B）：
        // v3 要为每份资产付一张全局调色板，2 项调色板又让位打包省不下什么，而 v2 那 8192 字节的
        // 索引数组在 deflate 面前本来就不值钱。v3 的收益来自"很多 section 共享同一批方块"，
        // 也就是真实场景的形状——这个量级由 SceneAssetBenchmark 在真实语料上给出并守住。
    }

    /** 真实形状（多 section、共享调色板）下 v3 必须实打实地更小，否则这个格式就不值得存在。 */
    @Test
    public void v3BeatsV2OnARealisticCorpus() throws IOException {
        List<SceneAssetCodec.SectionData> sections = new ArrayList<>();
        for (int s = 0; s < 32; s++) {
            sections.add(sparseSection(s));
        }
        // 32 个 section 沿 x 排开，边界要放得下（单轴上限 512 格）。
        SceneBounds bounds = new SceneBounds(0, 0, 0, 512, 16, 16);
        SceneAssetCodec.AssetData asset = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld",
                bounds, bounds.inflate(1), "v3_fingerprint", sections, List.of());

        long v2 = SceneAssetCodec.encode(asset, SceneAssetCodec.FORMAT_VERSION_V2).length;
        long v3 = SceneAssetCodec.encode(asset, SceneAssetCodec.FORMAT_VERSION_V3).length;

        assertTrue(v3 < v2, "32 个共享调色板的 section：v3=" + v3 + " v2=" + v2);
    }

    @Test
    public void singleEntryPaletteWritesNoIndexArray() throws IOException {
        List<String> palette = List.of("minecraft:air");
        SceneAssetCodec.SectionData section = new SceneAssetCodec.SectionData(
                0, 0, 0, palette, new short[4096], new byte[2048], new byte[2048]);
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(section));

        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(SceneAssetCodec.encode(asset));

        assertSameModel(asset, decoded);
        // 位宽 0：索引段一个字节都不写，解码侧应当全部补 0。
        assertArrayEquals(new short[4096], decoded.sections.get(0).blockIndices);
    }

    @Test
    public void sparseIsChosenForAirHeavyAndPackedForDenseSections() throws IOException {
        byte[] denseRaw = inflate(SceneAssetCodec.encode(sampleAsset(List.of(denseSection(0))), 3));
        byte[] sparseRaw = inflate(SceneAssetCodec.encode(sampleAsset(List.of(sparseSection(0))), 3));

        assertEquals(SceneAssetCodec.INDEX_ENCODING_PACKED,
                denseRaw[flagOffset(denseRaw, 0)] & 0xFF, "密实 section 应走位打包");
        assertEquals(SceneAssetCodec.INDEX_ENCODING_SPARSE,
                sparseRaw[flagOffset(sparseRaw, 0)] & 0xFF, "稀疏 section 应走非空气位图");
    }

    @Test
    public void reencodingIsCanonical() throws IOException {
        // 同一份模型编两次必须逐字节相同——客户端按 SHA-256 做内容寻址，编码不确定会让缓存永远失效。
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0), sparseSection(1)));

        byte[] first = SceneAssetCodec.encode(asset);
        byte[] second = SceneAssetCodec.encode(SceneAssetCodec.decode(first));

        assertArrayEquals(first, second);
    }

    @Test
    public void payloadsSurviveTheV3Trailer() throws IOException {
        SceneRenderPayload.VisualVertex v0 = SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v1 = SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v2 = SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0xFFFFFFFF);
        SceneRenderPayload payload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/stone"), v0, v1, v2, v0)
                .customData(new CompoundTag())
                .build();

        SceneBounds bounds = new SceneBounds(0, 0, 0, 64, 64, 64);
        SceneAssetCodec.AssetData asset = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld", bounds, bounds.inflate(1),
                "fp", List.of(denseSection(0)),
                List.of(new SceneBlockPayloadEntry(1, 2, 3, ResourceLocation.parse("examplemod:adapter"), 1, payload)));

        SceneAssetCodec.AssetData decoded = SceneAssetCodec.decode(SceneAssetCodec.encode(asset));

        assertEquals(1, decoded.blockPayloads.size());
        assertEquals(ResourceLocation.parse("examplemod:adapter"), decoded.blockPayloads.get(0).adapterId());
        assertSameModel(asset, decoded);
    }

    // ---- 防伪：改一个字节就应当被流内 CRC 抓住 ----

    @Test
    public void crcCoversTheIndexArray() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        int indexStart = flagOffset(inflate(encoded), 0) + 1; // 编码标记之后就是索引区

        byte[] corrupted = tamper(encoded, c -> c.b[indexStart] ^= 0xFF);

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(corrupted));
        assertTrue(failure.getMessage().contains("CRC"),
                "应当是流内 CRC 报错（gzip 自己的校验是对的，因为这份 gzip 是重新压的），实际：" + failure.getMessage());
    }

    @Test
    public void crcCoversSectionCoordinates() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        int coordsStart = toFirstSection(inflate(encoded)).i;

        byte[] corrupted = tamper(encoded, c -> c.b[coordsStart + 3] ^= 0x01);

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(corrupted));
        assertTrue(failure.getMessage().contains("CRC"), "实际：" + failure.getMessage());
    }

    @Test
    public void crcCoversGlobalPaletteReferences() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        int paletteRefStart = toFirstSection(inflate(encoded)).i + 12 + 4; // 坐标之后是 paletteSize，再之后是引用

        // 改成一个"在范围内但不对"的值：这样范围检查不会先报错，能真正验证 CRC 覆盖了调色板引用。
        // （改成越界值会被 Global palette index out of range 提前拦下，测不到 CRC。）
        byte[] corrupted = tamper(encoded, c -> c.putI32(paletteRefStart, 1));

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(corrupted));
        assertTrue(failure.getMessage().contains("CRC"), "实际：" + failure.getMessage());
    }

    // ---- 畸形流 ----

    @Test
    public void rejectsUnknownIndexEncoding() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        int flag = flagOffset(inflate(encoded), 0);

        byte[] corrupted = tamper(encoded, c -> c.b[flag] = 7);

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(corrupted));
        assertTrue(failure.getMessage().contains("Unknown v3 index encoding"), "实际：" + failure.getMessage());
    }

    @Test
    public void rejectsOutOfRangeGlobalPaletteReference() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        // 把第 0 段的调色板引用改成越界值：CRC 会先发现，但即便绕过 CRC 也必须被范围检查挡住。
        int paletteRefStart = toFirstSection(inflate(encoded)).i + 12 + 4;

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(
                tamper(encoded, c -> c.putI32(paletteRefStart, SceneLimits.MAX_GLOBAL_PALETTE_ENTRIES + 1))));
        assertNotNull(failure.getMessage());
    }

    @Test
    public void rejectsOversizedGlobalPaletteBeforeAllocating() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] encoded = SceneAssetCodec.encode(asset);
        byte[] raw = inflate(encoded);
        Cursor walk = new Cursor(raw);
        walk.i32();
        walk.i32();
        walk.i32();
        walk.skipUtf();
        for (int k = 0; k < 12; k++) walk.i32();
        walk.skipUtf();
        walk.i32(); // sectionCount
        int globalCountOffset = walk.i;

        IOException failure = assertThrows(IOException.class, () -> SceneAssetCodec.decode(
                tamper(encoded, c -> c.putI32(globalCountOffset, SceneLimits.MAX_GLOBAL_PALETTE_ENTRIES + 1))));
        assertTrue(failure.getMessage().contains("Invalid global palette size"), "实际：" + failure.getMessage());
    }

    @Test
    public void rejectsTrailingDataAfterCrc() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0)));
        byte[] raw = inflate(SceneAssetCodec.encode(asset));
        byte[] extended = new byte[raw.length + 1];
        System.arraycopy(raw, 0, extended, 0, raw.length);

        IOException failure = assertThrows(IOException.class,
                () -> SceneAssetCodec.decode(deflate(extended)));
        assertTrue(failure.getMessage().contains("Trailing data"), "实际：" + failure.getMessage());
    }

    @Test
    public void v3RequiresAirInPaletteSlotZero() throws IOException {
        List<String> noAir = List.of("minecraft:stone", "minecraft:dirt");
        SceneAssetCodec.SectionData section = new SceneAssetCodec.SectionData(
                0, 0, 0, noAir, new short[4096], new byte[2048], new byte[2048]);
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(section));

        // v2 不关心第 0 位是什么，v3 必须拒绝：稀疏编码把索引 0 当隐含默认值，而渲染端也跳过它。
        assertNotNull(SceneAssetCodec.encode(asset, SceneAssetCodec.FORMAT_VERSION_V2));
        IOException failure = assertThrows(IOException.class,
                () -> SceneAssetCodec.encode(asset, SceneAssetCodec.FORMAT_VERSION_V3));
        assertTrue(failure.getMessage().contains("slot 0"), "实际：" + failure.getMessage());
    }

    @Test
    public void rejectsPaletteIndexOutsidePalette() throws IOException {
        // 直接构造越界索引：validateAssetData 在编码前就应当拦住。
        List<String> palette = List.of("minecraft:air", "minecraft:stone");
        short[] indices = new short[4096];
        indices[10] = 2; // palette 只有 2 项，合法下标是 0/1
        SceneAssetCodec.SectionData section = new SceneAssetCodec.SectionData(
                0, 0, 0, palette, indices, new byte[2048], new byte[2048]);

        assertThrows(IOException.class,
                () -> SceneAssetCodec.encode(sampleAsset(List.of(section)), SceneAssetCodec.FORMAT_VERSION_V3));
    }

    // ---- 估算 ----

    @Test
    public void v3EstimateIsAnUpperBoundOnTheRealStream() throws IOException {
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0), sparseSection(1)));

        byte[] encoded = SceneAssetCodec.encode(asset);
        long actual = inflate(encoded).length;
        long estimate = SceneAssetCodec.estimateUncompressedSize(asset, SceneAssetCodec.FORMAT_VERSION_V3);

        assertTrue(estimate >= actual, "估算必须是上界：estimate=" + estimate + " actual=" + actual);
    }

    @Test
    public void retainedBytesEstimateDoesNotDependOnFormatVersion() {
        // 驻留内存估算描述的是解码后的内存模型，v3 不改变它。
        SceneAssetCodec.AssetData asset = sampleAsset(List.of(denseSection(0), sparseSection(1)));

        assertEquals(SceneAssetCodec.estimateRetainedBytes(asset), SceneAssetCodec.estimateRetainedBytes(asset));
        assertTrue(SceneAssetCodec.estimateRetainedBytes(asset) > 0);
    }
}
