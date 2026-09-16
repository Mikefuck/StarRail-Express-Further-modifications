package com.habitrain.core.scene.asset;

import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
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
 * Section 增量补丁的读写、应用与拒绝路径。
 *
 * <p>最关键的一条断言是"补丁应用出来的模型与整份资产逐字段相同（含 Section 顺序）"——
 * 半透明绘制顺序依赖 Section 顺序，这条不能靠假设，必须钉住。</p>
 */
class SceneAssetDeltaTest {

    private static final SceneBounds BOUNDS = new SceneBounds(0, 0, 0, 64, 64, 64);
    private static final String BASE_HASH = "a".repeat(64);
    private static final String TARGET_HASH = "b".repeat(64);

    // ---- 语料 ----

    /** 密实 section：2 项调色板（1 bit）→ 走位打包。 */
    private static SceneAssetCodec.SectionData dense(int relX, int relY, int relZ, int variant) {
        List<String> palette = List.of("minecraft:air", "minecraft:stone");
        short[] indices = new short[4096];
        for (int i = 0; i < 4096; i++) {
            indices[i] = (short) ((i % 7 == variant) ? 0 : 1);
        }
        return new SceneAssetCodec.SectionData(relX, relY, relZ, palette, indices,
                light(variant), light(variant + 1));
    }

    /** 稀疏 section：17 项调色板（8 bit）、只有零星方块 → 走非空气位图。 */
    private static SceneAssetCodec.SectionData sparse(int relX, int relY, int relZ) {
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        for (int i = 1; i < 17; i++) {
            palette.add("minecraft:block_" + i);
        }
        short[] indices = new short[4096];
        indices[0] = 5;
        indices[1234] = 9;
        indices[4095] = 16;
        return new SceneAssetCodec.SectionData(relX, relY, relZ, palette, indices,
                new byte[2048], new byte[2048]);
    }

    private static byte[] light(int seed) {
        byte[] data = new byte[2048];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return data;
    }

    private static SceneAssetCodec.AssetData asset(List<SceneAssetCodec.SectionData> sections) {
        return new SceneAssetCodec.AssetData(SceneAssetCodec.FORMAT_VERSION_V3, 3955,
                "minecraft:overworld", BOUNDS, BOUNDS, "fingerprint-1", sections, List.of());
    }

    private static List<SceneAssetCodec.SectionData> sections(SceneAssetCodec.SectionData... values) {
        List<SceneAssetCodec.SectionData> list = new ArrayList<>();
        for (SceneAssetCodec.SectionData value : values) {
            list.add(value);
        }
        return list;
    }

    // ---- 正向路径 ----

    @Test
    void reconstructedTargetIsByteExactAndCanBecomeTheNextBase() throws IOException {
        var base = asset(sections(dense(0, 0, 0, 0)));
        var target = asset(sections(dense(0, 0, 0, 1)));
        byte[] published = SceneAssetCodec.encode(target);
        String hash = SceneAssetCodec.calculateSha256(published);
        var descriptor = new SceneAssetDescriptor(hash, 0, published.length,
                target.sections.size(), target.dataVersion, target.fingerprint, 1);
        var patch = SceneAssetDelta.build(base, BASE_HASH, target, hash);
        var merged = SceneAssetDelta.apply(base, patch);
        byte[] restored = SceneAssetDelta.encodeVerifiedTarget(merged, descriptor);
        assertArrayEquals(published, restored);
        var next = asset(sections(dense(0, 0, 0, 2)));
        assertNotNull(SceneAssetDelta.build(SceneAssetCodec.decode(restored), hash, next, TARGET_HASH));
    }

    @Test
    void sameSectionCountDoesNotMakeWrongPatchContentValid() throws IOException {
        var target = asset(sections(dense(0, 0, 0, 1)));
        byte[] published = SceneAssetCodec.encode(target);
        var descriptor = new SceneAssetDescriptor(SceneAssetCodec.calculateSha256(published),
                0, published.length, 1, target.dataVersion, target.fingerprint, 1);
        var wrong = asset(sections(dense(0, 0, 0, 2)));
        assertThrows(IOException.class, () -> SceneAssetDelta.encodeVerifiedTarget(wrong, descriptor));
    }

    @Test
    void applyRebuildsTargetModelExactly() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(
                dense(0, 0, 0, 0), dense(1, 0, 0, 0), sparse(2, 0, 0), sparse(0, 0, 1)));
        // 目标列表必须按捕获序 (relX, relZ, relY) 排列——真实资产就是这样生成的，
        // 而补丁的自检会拒绝顺序对不上的一致性。
        SceneAssetCodec.AssetData target = asset(sections(
                dense(0, 0, 0, 0),             // 不变
                sparse(0, 0, 1),               // 保留（base 里也有且内容一致）
                dense(1, 0, 0, 1),             // 改内容
                sparse(3, 0, 0)));             // 新增（base 里没有）

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta, "有 Section 变化时应当产出补丁");
        assertEquals(1, delta.removedKeys().size(), "只应删掉 (2,0,0)");
        assertEquals(2, delta.changedSections().size(), "应包含修改的 (1,0,0) 与新增的 (3,0,0)");
        assertEquals(4, delta.targetSectionCount());

        SceneAssetCodec.AssetData rebuilt = SceneAssetDelta.apply(base, delta);
        assertTrue(SceneAssetDelta.sameModel(target, rebuilt), "应用补丁后必须与目标模型逐字段相同");
    }

    @Test
    void encodeDecodeRoundTripKeepsModel() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(0, 0, 0, 0), sparse(1, 0, 0)));

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);

        byte[] encoded = SceneAssetDelta.encode(delta);
        SceneAssetDelta.DeltaData decoded = SceneAssetDelta.decode(encoded);

        assertEquals(delta.baseSha256(), decoded.baseSha256());
        assertEquals(delta.targetSha256(), decoded.targetSha256());
        assertEquals(delta.targetSectionCount(), decoded.targetSectionCount());
        assertEquals(delta.fingerprint(), decoded.fingerprint());
        assertEquals(delta.dimensionId(), decoded.dimensionId());
        assertEquals(delta.sourceBounds(), decoded.sourceBounds());
        assertEquals(delta.removedKeys(), decoded.removedKeys());
        assertEquals(delta.changedSections().size(), decoded.changedSections().size());

        SceneAssetCodec.AssetData rebuilt = SceneAssetDelta.apply(base, decoded);
        assertTrue(SceneAssetDelta.sameModel(target, rebuilt), "编码往返后应用结果仍须等于目标模型");
    }

    /** 稀疏位图分支与位打包分支都要走一遍补丁路径（两者由编码器按解析式选择）。 */
    @Test
    void roundTripsBothIndexEncodings() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.decode(
                SceneAssetDelta.encode(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH)));
        SceneAssetCodec.SectionData changed = delta.changedSections().get(0);
        assertEquals(17, changed.palette.size());
        assertEquals(16, changed.blockIndices[4095], "最后一个格子必须往返一致");
        assertTrue(SceneAssetDelta.sameModel(target, SceneAssetDelta.apply(base, delta)));
    }

    /**
     * 应用结果必须按捕获遍历序 (relX, relZ, relY) 排列，而不是 base 的原顺序。
     * 顺序决定半透明绘制次序，所以这里钉的是"补丁客户端与全量客户端画出来是同一张图"。
     */
    @Test
    void applyRebuildsCaptureOrderNotBaseOrder() throws IOException {
        SceneAssetCodec.SectionData unchanged = dense(1, 0, 0, 0);
        SceneAssetCodec.SectionData before = dense(0, 0, 0, 0);
        SceneAssetCodec.SectionData after = dense(0, 0, 0, 3);

        // base 的列表顺序是反的；目标按捕获序。
        SceneAssetCodec.AssetData base = asset(sections(unchanged, before));
        SceneAssetCodec.AssetData target = asset(sections(after, unchanged));

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);
        SceneAssetCodec.AssetData rebuilt = SceneAssetDelta.apply(base, delta);

        assertEquals(0, rebuilt.sections.get(0).relX, "应用结果的第一项应是 relX 最小的那个");
        assertEquals(1, rebuilt.sections.get(1).relX);
        assertTrue(SceneAssetDelta.sameModel(target, rebuilt));
    }

    /** 选区整体平移（同一片世界、rel 坐标全变）也必须能正确重建——只是补丁会很大。 */
    @Test
    void handlesShiftedBounds() throws IOException {
        SceneBounds shifted = new SceneBounds(16, 0, 0, 80, 64, 64);
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld", shifted, shifted,
                "fingerprint-1", sections(dense(0, 0, 0, 2), sparse(2, 0, 0)), List.of());

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta, "坐标平移属于内容变化，仍应产出（正确性由自检保证）");
        assertTrue(SceneAssetDelta.sameModel(target, SceneAssetDelta.apply(base, delta)));
    }

    /**
     * 真实语料里存在**存储顺序不等于捕获序**的老资产（v1 时代留下的），而且往往正是最大的那几个。
     * 补丁靠"保留/变更"位图按目标序交织重建，不依赖任何排序假设，这些资产同样能拿到增量。
     */
    @Test
    void handlesAssetsWhoseStoredOrderIsNotCaptureOrder() throws IOException {
        // 两个版本都用同一套"非捕获序"：[先 (1,0,0)，再 (0,0,0)]。
        SceneAssetCodec.AssetData base = asset(sections(dense(1, 0, 0, 0), dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(1, 0, 0, 0), sparse(0, 0, 0)));

        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta, "顺序非常规不该让补丁消失——真实语料里最大的资产就是这种");

        SceneAssetCodec.AssetData rebuilt = SceneAssetDelta.apply(base, delta);
        assertTrue(SceneAssetDelta.sameModel(target, rebuilt));
        assertEquals(1, rebuilt.sections.get(0).relX, "第 0 项必须还是 (1,0,0)");

        SceneAssetCodec.AssetData roundTripped = SceneAssetDelta.apply(base,
                SceneAssetDelta.decode(SceneAssetDelta.encode(delta)));
        assertTrue(SceneAssetDelta.sameModel(target, roundTripped), "位图必须能往返");
    }

    /** 位图与"保留池"对不上时必须拒绝，而不是拼出一个缺斤少两的模型。 */
    @Test
    void applyRejectsInconsistentRetainedSplit() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(0, 0, 0, 0), sparse(1, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);

        boolean[] wrong = delta.retainedFlags();
        wrong[1] = true; // 把一个"变更"位谎报成"保留"
        SceneAssetDelta.DeltaData lying = new SceneAssetDelta.DeltaData(
                delta.baseSha256(), delta.targetSha256(), delta.dataVersion(), delta.dimensionId(),
                delta.sourceBounds(), delta.haloBounds(), delta.fingerprint(),
                delta.targetSectionCount(), delta.removedKeys(), delta.changedSections(), wrong);
        assertThrows(IOException.class, () -> SceneAssetDelta.apply(base, lying));
    }

    // ---- 不该产出补丁的情形 ----

    @Test
    void identicalModelsProduceNoDelta() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        assertNull(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));
    }

    @Test
    void fingerprintMismatchProducesNoDelta() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld", BOUNDS, BOUNDS,
                "fingerprint-2", sections(dense(1, 0, 0, 0)), List.of());
        assertNull(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));
    }

    @Test
    void dataVersionMismatchProducesNoDelta() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 4000, "minecraft:overworld", BOUNDS, BOUNDS,
                "fingerprint-1", sections(dense(1, 0, 0, 0)), List.of());
        assertNull(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));
    }

    @Test
    void blockPayloadsDisableDelta() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneRenderPayload.VisualVertex v0 = SceneRenderPayload.VisualVertex.of(0, 0, 0, 0, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v1 = SceneRenderPayload.VisualVertex.of(1, 0, 0, 1, 0, 0xFFFFFFFF);
        SceneRenderPayload.VisualVertex v2 = SceneRenderPayload.VisualVertex.of(1, 1, 0, 1, 1, 0xFFFFFFFF);
        SceneRenderPayload payload = SceneRenderPayload.builder()
                .addQuad(ResourceLocation.parse("minecraft:block/stone"), v0, v1, v2, v0)
                .customData(new CompoundTag())
                .build();
        SceneBlockPayloadEntry entry = new SceneBlockPayloadEntry(1, 2, 3,
                ResourceLocation.parse("examplemod:adapter"), 1, payload);
        SceneAssetCodec.AssetData target = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld", BOUNDS, BOUNDS,
                "fingerprint-1", sections(dense(1, 0, 0, 0)), List.of(entry));
        assertNull(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH),
                "带视觉载荷的资产不参与增量（补丁不承载载荷）");
    }

    @Test
    void nonV3TargetProducesNoDelta() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V2, 3955, "minecraft:overworld", BOUNDS, BOUNDS,
                "fingerprint-1", sections(dense(1, 0, 0, 0)), List.of());
        assertNull(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));
    }

    // ---- 应用侧的拒绝路径 ----

    @Test
    void applyRejectsBaseWithDifferentFingerprint() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);

        SceneAssetCodec.AssetData wrongBase = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:overworld", BOUNDS, BOUNDS,
                "fingerprint-9", sections(dense(0, 0, 0, 0)), List.of());
        assertThrows(IOException.class, () -> SceneAssetDelta.apply(wrongBase, delta));
    }

    @Test
    void applyRejectsWrongTargetSectionCount() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(0, 0, 0, 0), sparse(1, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);

        SceneAssetDelta.DeltaData lying = new SceneAssetDelta.DeltaData(
                delta.baseSha256(), delta.targetSha256(), delta.dataVersion(), delta.dimensionId(),
                delta.sourceBounds(), delta.haloBounds(), delta.fingerprint(),
                delta.targetSectionCount() + 1, delta.removedKeys(), delta.changedSections());
        assertThrows(IOException.class, () -> SceneAssetDelta.apply(base, lying));
    }

    @Test
    void applyRejectsDeltaFromAnotherDimension() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);

        SceneAssetCodec.AssetData netherBase = new SceneAssetCodec.AssetData(
                SceneAssetCodec.FORMAT_VERSION_V3, 3955, "minecraft:the_nether", BOUNDS, BOUNDS,
                "fingerprint-1", sections(dense(0, 0, 0, 0)), List.of());
        assertThrows(IOException.class, () -> SceneAssetDelta.apply(netherBase, delta));
    }

    // ---- 解码侧的防伪路径 ----

    @Test
    void decodeRejectsTamperedIndexBytes() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        byte[] encoded = SceneAssetDelta.encode(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));

        byte[] raw = inflate(encoded);
        // 结构：MAGIC(4) VERSION(4) UTF base(2+64) UTF target(2+64) dataVersion(4) UTF dim(2+len)
        //       6×4 bounds ×2 UTF fingerprint(2+len) sectionCount(4) removedCount(4) changedCount(4)
        //       然后就是 section 记录：坐标 3×4、paletteSize 4、palette 字符串、编码字节、索引段…
        int paletteSizeOffset = fingerprintAndCountsEnd(raw);
        int paletteSize = readInt(raw, paletteSizeOffset);
        assertEquals(17, paletteSize, "测试语料是 17 项调色板");

        int paletteEnd = paletteSizeOffset + 4;
        for (int i = 0; i < paletteSize; i++) {
            int length = ((raw[paletteEnd] & 0xFF) << 8) | (raw[paletteEnd + 1] & 0xFF);
            paletteEnd += 2 + length; // writeUTF 的 modified UTF-8，这里全是 ASCII
        }
        int encodingOffset = paletteEnd;
        int indexStart = encodingOffset + 1;
        raw[indexStart] ^= 0x40; // 只翻索引段里的一个 bit

        byte[] tampered = deflate(raw);
        assertThrows(IOException.class, () -> SceneAssetDelta.decode(tampered),
                "索引字节被改动必须被流内 CRC 检出");
    }

    @Test
    void decodeRejectsTrailingData() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        byte[] encoded = SceneAssetDelta.encode(SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH));

        byte[] raw = inflate(encoded);
        byte[] extended = new byte[raw.length + 3];
        System.arraycopy(raw, 0, extended, 0, raw.length);
        assertThrows(IOException.class, () -> SceneAssetDelta.decode(deflate(extended)));
    }

    @Test
    void decodeRejectsUnsupportedVersion() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        byte[] raw = inflate(SceneAssetDelta.encode(
                SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH)));

        // MAGIC 之后再写一个假的版本号
        raw[4] = 0;
        raw[5] = 0;
        raw[6] = 0;
        raw[7] = 99;
        assertThrows(IOException.class, () -> SceneAssetDelta.decode(deflate(raw)));
    }

    @Test
    void decodeRejectsPaletteSlotZeroThatIsNotAir() throws IOException {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        byte[] raw = inflate(SceneAssetDelta.encode(
                SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH)));

        // 把第一个调色板项 "minecraft:air"(13 字符) 改成 "minecraft:aaa"：长度不变，内容变。
        int paletteEnd = fingerprintAndCountsEnd(raw) + 4;
        int length = ((raw[paletteEnd] & 0xFF) << 8) | (raw[paletteEnd + 1] & 0xFF);
        assertEquals(13, length);
        raw[paletteEnd + 2 + 10] = 'a';
        raw[paletteEnd + 2 + 11] = 'a';
        raw[paletteEnd + 2 + 12] = 'a';

        assertThrows(IOException.class, () -> SceneAssetDelta.decode(deflate(raw)),
                "调色板第 0 项不是空气时必须拒绝（稀疏编码把索引 0 当隐含默认值）");
    }

    @Test
    void encodeRejectsBadHashes() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(sparse(0, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        SceneAssetDelta.DeltaData bad = new SceneAssetDelta.DeltaData(
                "not-a-hash", delta.targetSha256(), delta.dataVersion(), delta.dimensionId(),
                delta.sourceBounds(), delta.haloBounds(), delta.fingerprint(),
                delta.targetSectionCount(), delta.removedKeys(), delta.changedSections());
        assertThrows(IOException.class, () -> SceneAssetDelta.encode(bad));
    }

    // ---- 比较器本身 ----

    @Test
    void sameModelDetectsSectionOrderDifference() {
        SceneAssetCodec.AssetData forward = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData reversed = asset(sections(dense(1, 0, 0, 0), dense(0, 0, 0, 0)));
        assertFalse(SceneAssetDelta.sameModel(forward, reversed),
                "顺序不同必须判定为不同模型——半透明绘制顺序依赖它");
    }

    @Test
    void describeMentionsCounts() {
        SceneAssetCodec.AssetData base = asset(sections(dense(0, 0, 0, 0), dense(1, 0, 0, 0)));
        SceneAssetCodec.AssetData target = asset(sections(dense(0, 0, 0, 0)));
        SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, BASE_HASH, target, TARGET_HASH);
        assertNotNull(delta);
        String text = SceneAssetDelta.describe(delta);
        assertTrue(text.contains("删除 1"), text);
        assertEquals("无补丁", SceneAssetDelta.describe(null));
    }

    // ---- 字节工具 ----

    /**
     * 补丁头的长度（到 changedCount 为止），也就是第一个 section 记录的起始位置。
     * 头部字段全是定长或 UTF，因此可以按字段精确算出来。
     */
    private static int fingerprintAndCountsEnd(byte[] raw) {
        int offset = 8;                                   // MAGIC + PATCH_VERSION
        offset = skipUtf(raw, offset);                    // baseSha256
        offset = skipUtf(raw, offset);                    // targetSha256
        offset += 4;                                      // dataVersion
        offset = skipUtf(raw, offset);                    // dimensionId
        offset += 48;                                     // sourceBounds + haloBounds（各 6 个 int）
        offset = skipUtf(raw, offset);                    // fingerprint
        int targetSectionCount = readInt(raw, offset);
        offset += 4 + (targetSectionCount + 7) / 8;       // targetSectionCount + 保留位图
        int removedCount = readInt(raw, offset);
        offset += 4;
        offset += removedCount * 12;                      // 删除的坐标
        int changedCount = readInt(raw, offset);
        assertEquals(1, changedCount, "测试语料只改了一个 Section");
        offset += 4;
        offset += 12;                                     // 第一个 section 的坐标
        return offset;                                    // 指向 paletteSize
    }

    private static int skipUtf(byte[] raw, int offset) {
        int length = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
        return offset + 2 + length;
    }

    private static int readInt(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24) | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
    }

    private static byte[] inflate(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
            gz.finish();
            return out.toByteArray();
        }
    }
}
