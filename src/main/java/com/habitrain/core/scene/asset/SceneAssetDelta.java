package com.habitrain.core.scene.asset;

import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 场景资产的 Section 级增量补丁（.hpatch）。
 *
 * <p>用途：服务端重新发布同一张地图的场景时，客户端往往已经持有上一版资产。补丁只携带
 * <b>相对上一版发生变化的 Section</b>，客户端把它应用在本地那份 base 上就得到新版模型，
 * 从而省掉整份资产的下行字节。补丁是纯附加的：全量资产照旧发布，客户端应用失败一律回退
 * 整文件下载。</p>
 *
 * <p><b>为什么不是"逐 Section 独立压缩 + 偏移表"</b>：那会牺牲跨 Section 的 deflate 匹配，
 * 而 v3 的收益恰恰来自跨 Section 共享（全局调色板 + 同类字段相邻）。这里改成语义补丁——
 * 只传变化的 Section，未变的 Section 连字节都不用传，压缩率不受影响。</p>
 *
 * <p><b>坐标键而不是下标</b>：补丁按 {@code (relX, relY, relZ)} 匹配两个版本的 Section。
 * 捕获顺序会变（选区、空 Section 跳过），下标不可靠。</p>
 *
 * <p><b>顺序靠位图重建，不靠任何排序假设</b>：补丁携带一个"目标资产里哪些 Section 是原样
 * 沿用 base"的位图；{@link #apply} 按目标序走一遍，保留位就从 base 剩下的 Section 里按原
 * 顺序取，变更位就取补丁里的下一个。{@link #build} 再把重建结果与目标模型<b>逐字段、含顺序</b>
 * 比对一次，不一致就不产出补丁。</p>
 *
 * <p>为什么要这么麻烦：真实语料里存在**存储顺序不等于捕获序**的老资产（v1 时代留下的），
 * 而且往往正是最大的那几个——按坐标重排的规则会让它们永远拿不到补丁。半透明绘制顺序依赖
 * Section 顺序，所以顺序这件事必须精确重建，不能"差不多就行"。</p>
 */
public final class SceneAssetDelta {

    public static final int MAGIC = 0x4853444C; // "HSDL"
    public static final int PATCH_VERSION = 1;

    /** 补丁与资产共用同一套体积上限：它就是另一份要被分片传输的 gzip 文件。 */
    public static final int MAX_PATCH_BYTES = SceneAssetCodec.MAX_COMPRESSED_BYTES;
    public static final long MAX_PATCH_UNCOMPRESSED_BYTES = SceneAssetCodec.MAX_UNCOMPRESSED_BYTES;

    private SceneAssetDelta() {}

    /** Reconstruct the exact published bytes or let the caller fall back to full transfer. */
    public static byte[] encodeVerifiedTarget(SceneAssetCodec.AssetData merged,
                                               SceneAssetDescriptor target) throws IOException {
        if (merged.sections.size() != target.sectionCount()
                || merged.dataVersion != target.dataVersion()
                || !merged.fingerprint.equals(target.fingerprint())) {
            throw new IOException("Scene patch result metadata mismatch");
        }
        byte[] bytes = SceneAssetCodec.encode(merged);
        if (bytes.length != target.compressedSize()
                || !SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(target.sha256())) {
            throw new IOException("Scene patch result SHA-256 mismatch");
        }
        return bytes;
    }

    /** 传输路径的哈希形态，与服务端分片授权/文件名规则一致。 */
    private static final String HASH_PATTERN = "[0-9a-fA-F]{64}";

    // ---- 模型 ----

    /** Section 在资产网格中的相对坐标，补丁的一切匹配都以它为准。 */
    public record SectionKey(int relX, int relY, int relZ) {}

    /**
     * 补丁的不可变模型：目标资产的元数据 + 被删除的 Section 键 + 新增/修改的 Section。
     */
    public static final class DeltaData {
        private final String baseSha256;
        private final String targetSha256;
        private final int dataVersion;
        private final String dimensionId;
        private final SceneBounds sourceBounds;
        private final SceneBounds haloBounds;
        private final String fingerprint;
        private final int targetSectionCount;
        private final List<SectionKey> removedKeys;
        private final List<SceneAssetCodec.SectionData> changedSections;
        /**
         * 目标资产的 Section 顺序里，哪一些是"原样沿用 base"的。
         *
         * <p>有它才能<b>不依赖任何排序假设</b>地重建顺序：按目标序走一遍，遇到保留位就从 base
         * 剩下的 Section 里按原顺序取一个，遇到变更位就取补丁里的下一个。真实语料里确实存在
         * 存储顺序不等于捕获序的老资产（v1 时代留下的），只靠"按坐标重排"会让那些资产
         * 永远拿不到补丁——而它们往往正是最大的那几个。</p>
         */
        private final boolean[] retainedFlags;

        public DeltaData(String baseSha256, String targetSha256, int dataVersion, String dimensionId,
                         SceneBounds sourceBounds, SceneBounds haloBounds, String fingerprint,
                         int targetSectionCount, List<SectionKey> removedKeys,
                         List<SceneAssetCodec.SectionData> changedSections) {
            this(baseSha256, targetSha256, dataVersion, dimensionId, sourceBounds, haloBounds, fingerprint,
                    targetSectionCount, removedKeys, changedSections, null);
        }

        public DeltaData(String baseSha256, String targetSha256, int dataVersion, String dimensionId,
                         SceneBounds sourceBounds, SceneBounds haloBounds, String fingerprint,
                         int targetSectionCount, List<SectionKey> removedKeys,
                         List<SceneAssetCodec.SectionData> changedSections, boolean[] retainedFlags) {
            this.baseSha256 = normalizeHash(baseSha256);
            this.targetSha256 = normalizeHash(targetSha256);
            this.dataVersion = dataVersion;
            this.dimensionId = dimensionId != null ? dimensionId : "minecraft:overworld";
            this.sourceBounds = sourceBounds != null ? sourceBounds : SceneBounds.EMPTY;
            this.haloBounds = haloBounds != null ? haloBounds : this.sourceBounds;
            this.fingerprint = fingerprint != null ? fingerprint : "";
            this.targetSectionCount = Math.max(0, targetSectionCount);
            this.removedKeys = removedKeys != null
                    ? List.copyOf(removedKeys) : Collections.emptyList();
            this.changedSections = changedSections != null
                    ? List.copyOf(changedSections) : Collections.emptyList();
            if (retainedFlags != null) {
                this.retainedFlags = retainedFlags.clone();
            } else {
                // 缺省：前 (targetSectionCount - changed) 个保留、其余变更——只在测试里手工构造时用到。
                this.retainedFlags = new boolean[this.targetSectionCount];
                int retained = Math.max(0, this.targetSectionCount - this.changedSections.size());
                for (int i = 0; i < retained && i < this.retainedFlags.length; i++) {
                    this.retainedFlags[i] = true;
                }
            }
        }

        public boolean[] retainedFlags() {
            return retainedFlags.clone();
        }

        public boolean isRetained(int targetIndex) {
            return targetIndex >= 0 && targetIndex < retainedFlags.length && retainedFlags[targetIndex];
        }

        public int retainedCount() {
            int count = 0;
            for (boolean retained : retainedFlags) {
                if (retained) count++;
            }
            return count;
        }

        public String baseSha256() { return baseSha256; }
        public String targetSha256() { return targetSha256; }
        public int dataVersion() { return dataVersion; }
        public String dimensionId() { return dimensionId; }
        public SceneBounds sourceBounds() { return sourceBounds; }
        public SceneBounds haloBounds() { return haloBounds; }
        public String fingerprint() { return fingerprint; }
        public int targetSectionCount() { return targetSectionCount; }
        public List<SectionKey> removedKeys() { return removedKeys; }
        public List<SceneAssetCodec.SectionData> changedSections() { return changedSections; }

        /** 补丁本身要传的 Section 数（调用方用它判断"这份补丁值不值得发"）。 */
        public int touchedSectionCount() {
            return removedKeys.size() + changedSections.size();
        }
    }

    private static String normalizeHash(String hash) {
        return hash != null ? hash.trim().toLowerCase() : "";
    }

    // ---- 生成 ----

    /**
     * 由 base 与 target 两份模型生成补丁；**任何前置条件不满足都返回 null**（调用方照常发全量）。
     *
     * <p>返回 null 的情形：任一侧带方块视觉载荷（补丁不承载载荷，真实资产实测载荷恒为 0）、
     * 方块注册表指纹/数据版本/维度不一致、没有任何 Section 变化、或者<b>自检不过</b>——
     * 自检把 {@link #apply} 的结果与 target 逐字段（含顺序）比对，不相等就不产出补丁。</p>
     *
     * @param baseSha256   base 资产文件的 SHA-256（客户端用它确认自己手里那份就是补丁的底）
     * @param targetSha256 目标资产文件的 SHA-256
     */
    public static DeltaData build(SceneAssetCodec.AssetData base, String baseSha256,
                                  SceneAssetCodec.AssetData target, String targetSha256) {
        if (base == null || target == null) return null;
        if (!base.blockPayloads.isEmpty() || !target.blockPayloads.isEmpty()) return null;
        if (target.formatVersion != SceneAssetCodec.FORMAT_VERSION_V3) return null;
        if (!base.fingerprint.equals(target.fingerprint)) return null;
        if (base.dataVersion != target.dataVersion) return null;
        if (!base.dimensionId.equals(target.dimensionId)) return null;
        if (!baseSha256.matches(HASH_PATTERN) || !targetSha256.matches(HASH_PATTERN)) return null;
        if (target.sections.size() > SceneLimits.MAX_DECODE_SECTIONS) return null;

        Map<SectionKey, SceneAssetCodec.SectionData> baseByKey = indexByKey(base.sections);
        if (baseByKey == null) return null;

        List<SectionKey> removed = new ArrayList<>();
        for (SectionKey key : baseByKey.keySet()) {
            // 目标里没有这个 Section：它被删掉了（选区变小、区块变空都走这条）。
            if (!containsKey(target.sections, key)) {
                removed.add(key);
            }
        }

        List<SceneAssetCodec.SectionData> changed = new ArrayList<>();
        boolean[] retainedFlags = new boolean[target.sections.size()];
        int index = 0;
        for (SceneAssetCodec.SectionData section : target.sections) {
            SectionKey key = new SectionKey(section.relX, section.relY, section.relZ);
            SceneAssetCodec.SectionData baseSection = baseByKey.get(key);
            boolean retained = baseSection != null && sameContent(baseSection, section);
            retainedFlags[index++] = retained;
            if (!retained) {
                changed.add(section);
            }
        }

        if (removed.isEmpty() && changed.isEmpty()) return null;
        if (removed.size() + changed.size() > SceneLimits.MAX_DECODE_SECTIONS) return null;

        sortKeys(removed);

        DeltaData delta = new DeltaData(baseSha256, targetSha256, target.dataVersion, target.dimensionId,
                target.sourceBounds, target.haloBounds, target.fingerprint, target.sections.size(),
                removed, changed, retainedFlags);

        // 自检：补丁必须能原样重建目标模型（含 Section 顺序）。这一条把"编码 bug"挡在发布侧，
        // 而不是等客户端显示出错。代价是一次内存合并，相对于编码本身可以忽略。
        try {
            SceneAssetCodec.AssetData rebuilt = apply(base, delta);
            if (!sameModel(rebuilt, target)) return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return delta;
    }

    /** 删除键按捕获序遍历序排列，只为了让补丁字节对同一对输入稳定（不参与重建顺序）。 */
    private static final Comparator<SectionKey> KEY_ORDER =
            Comparator.comparingInt(SectionKey::relX)
                    .thenComparingInt(SectionKey::relZ)
                    .thenComparingInt(SectionKey::relY);

    private static void sortKeys(List<SectionKey> keys) {
        keys.sort(KEY_ORDER);
    }

    private static Map<SectionKey, SceneAssetCodec.SectionData> indexByKey(
            List<SceneAssetCodec.SectionData> sections) {
        Map<SectionKey, SceneAssetCodec.SectionData> map = new LinkedHashMap<>();
        for (SceneAssetCodec.SectionData section : sections) {
            if (section == null) return null;
            if (map.put(new SectionKey(section.relX, section.relY, section.relZ), section) != null) {
                return null; // 坐标重复：模型本身不合法，补丁不参与
            }
        }
        return map;
    }

    private static boolean containsKey(List<SceneAssetCodec.SectionData> sections, SectionKey key) {
        for (SceneAssetCodec.SectionData section : sections) {
            if (section.relX == key.relX() && section.relY == key.relY() && section.relZ == key.relZ()) {
                return true;
            }
        }
        return false;
    }

    /** 两个 Section 的内容是否一致（调色板、索引、两种光照）。 */
    public static boolean sameContent(SceneAssetCodec.SectionData a, SceneAssetCodec.SectionData b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!a.palette.equals(b.palette)) return false;
        return Arrays.equals(a.blockIndices, b.blockIndices)
                && Arrays.equals(a.skyLight, b.skyLight)
                && Arrays.equals(a.blockLight, b.blockLight);
    }

    /** 两份模型是否逐字段相同（含 Section 顺序）。 */
    public static boolean sameModel(SceneAssetCodec.AssetData a, SceneAssetCodec.AssetData b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.formatVersion != b.formatVersion || a.dataVersion != b.dataVersion) return false;
        if (!a.dimensionId.equals(b.dimensionId) || !a.fingerprint.equals(b.fingerprint)) return false;
        if (!a.sourceBounds.equals(b.sourceBounds) || !a.haloBounds.equals(b.haloBounds)) return false;
        if (a.sections.size() != b.sections.size()) return false;
        for (int i = 0; i < a.sections.size(); i++) {
            SceneAssetCodec.SectionData left = a.sections.get(i);
            SceneAssetCodec.SectionData right = b.sections.get(i);
            if (left.relX != right.relX || left.relY != right.relY || left.relZ != right.relZ) return false;
            if (!sameContent(left, right)) return false;
        }
        return a.blockPayloads.size() == b.blockPayloads.size();
    }

    // ---- 应用 ----

    /**
     * 把补丁应用到 base 上，得到目标模型。
     *
     * <p>先校验 base 与补丁声明的身份一致（SHA-256 / 指纹 / 数据版本 / 维度），再按坐标做
     * 删除、替换、插入，最后按捕获序重排。任何不一致都抛 {@link IOException}——调用方据此
     * 回退整文件下载，绝不把"半个模型"交给渲染。</p>
     */
    public static SceneAssetCodec.AssetData apply(SceneAssetCodec.AssetData base, DeltaData delta)
            throws IOException {
        if (base == null) throw new IOException("Scene delta base is missing");
        if (delta == null) throw new IOException("Scene delta is missing");
        if (!base.fingerprint.equals(delta.fingerprint())) {
            throw new IOException("Scene delta fingerprint does not match the base asset");
        }
        if (base.dataVersion != delta.dataVersion()) {
            throw new IOException("Scene delta data version does not match the base asset");
        }
        if (!base.dimensionId.equals(delta.dimensionId())) {
            throw new IOException("Scene delta dimension does not match the base asset");
        }
        if (!base.blockPayloads.isEmpty()) {
            throw new IOException("Scene delta cannot be applied to a base carrying block payloads");
        }

        // 保留池：base 里既没被删除、也没被替换掉的 Section，保持 base 的原顺序。
        Set<SectionKey> changedKeys = new HashSet<>();
        for (SceneAssetCodec.SectionData section : delta.changedSections()) {
            changedKeys.add(new SectionKey(section.relX, section.relY, section.relZ));
        }
        List<SceneAssetCodec.SectionData> retainedPool = new ArrayList<>();
        for (SceneAssetCodec.SectionData section : base.sections) {
            SectionKey key = new SectionKey(section.relX, section.relY, section.relZ);
            if (delta.removedKeys().contains(key) || changedKeys.contains(key)) continue;
            retainedPool.add(section);
        }

        int targetCount = delta.targetSectionCount();
        if (delta.retainedCount() != retainedPool.size()) {
            throw new IOException("Scene delta retained section count mismatch: "
                    + delta.retainedCount() + " != " + retainedPool.size());
        }
        if (delta.changedSections().size() != targetCount - delta.retainedCount()) {
            throw new IOException("Scene delta changed section count mismatch");
        }

        // 按目标顺序交织：保留位取保留池的下一个，变更位取补丁里的下一个。
        List<SceneAssetCodec.SectionData> sections = new ArrayList<>(targetCount);
        int retainedIndex = 0;
        int changedIndex = 0;
        for (int i = 0; i < targetCount; i++) {
            if (delta.isRetained(i)) {
                sections.add(retainedPool.get(retainedIndex++));
            } else {
                sections.add(delta.changedSections().get(changedIndex++));
            }
        }
        return new SceneAssetCodec.AssetData(SceneAssetCodec.FORMAT_VERSION_V3, delta.dataVersion(),
                delta.dimensionId(), delta.sourceBounds(), delta.haloBounds(), delta.fingerprint(),
                sections, Collections.emptyList());
    }

    // ---- 线格式 ----

    /**
     * 编码为 GZIP 压缩的补丁字节。
     *
     * <pre>
     * int    MAGIC = 0x4853444C ("HSDL")
     * int    PATCH_VERSION
     * UTF    baseSha256
     * UTF    targetSha256
     * int    dataVersion
     * UTF    dimensionId
     * 6×int  sourceBounds
     * 6×int  haloBounds
     * UTF    fingerprint
     * int    targetSectionCount
     * int    removedCount;  每条 3×int 相对坐标
     * int    changedCount;  每条 { 3×int 坐标, int paletteSize, UTF 调色板项…, byte 索引编码, 索引段, 天空光 2048, 方块光 2048 }
     * long   crc32（覆盖坐标、调色板项数与索引段、光照；不含调色板字符串，与 v3 同口径）
     * </pre>
     */
    public static byte[] encode(DeltaData delta) throws IOException {
        if (delta == null) throw new IOException("Scene delta is null");
        validateForEncode(delta);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzos)) {
            dos.writeInt(MAGIC);
            dos.writeInt(PATCH_VERSION);
            dos.writeUTF(delta.baseSha256());
            dos.writeUTF(delta.targetSha256());
            dos.writeInt(delta.dataVersion());
            dos.writeUTF(delta.dimensionId());
            writeBounds(dos, delta.sourceBounds());
            writeBounds(dos, delta.haloBounds());
            dos.writeUTF(delta.fingerprint());
            dos.writeInt(delta.targetSectionCount());

            CRC32 crc = new CRC32();
            byte[] scratch = new byte[4];

            byte[] retainedBitmap = packRetainedBitmap(delta);
            dos.write(retainedBitmap);
            crc.update(retainedBitmap);

            dos.writeInt(delta.removedKeys().size());
            for (SectionKey key : delta.removedKeys()) {
                SceneAssetCodec.writeIntCrc(dos, crc, scratch, key.relX());
                SceneAssetCodec.writeIntCrc(dos, crc, scratch, key.relY());
                SceneAssetCodec.writeIntCrc(dos, crc, scratch, key.relZ());
            }

            dos.writeInt(delta.changedSections().size());
            for (SceneAssetCodec.SectionData section : delta.changedSections()) {
                writeSection(dos, crc, scratch, section);
            }

            dos.writeLong(crc.getValue());
            dos.flush();
        }
        return baos.toByteArray();
    }

    private static void validateForEncode(DeltaData delta) throws IOException {
        if (!delta.baseSha256().matches(HASH_PATTERN) || !delta.targetSha256().matches(HASH_PATTERN)) {
            throw new IOException("Scene delta hashes must be 64 hex characters");
        }
        if (ResourceLocation.tryParse(delta.dimensionId()) == null) {
            throw new IOException("Invalid scene delta dimension id: " + delta.dimensionId());
        }
        SceneAssetCodec.requireStringLength("registry fingerprint", delta.fingerprint(),
                SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
        SceneAssetCodec.validateBounds(delta.sourceBounds(), delta.haloBounds());
        if (delta.targetSectionCount() < 0 || delta.targetSectionCount() > SceneLimits.MAX_DECODE_SECTIONS) {
            throw new IOException("Invalid delta target section count: " + delta.targetSectionCount());
        }
        int touched = delta.touchedSectionCount();
        if (touched <= 0 || touched > SceneLimits.MAX_DECODE_SECTIONS) {
            throw new IOException("Invalid delta section count: " + touched);
        }
        Set<SectionKey> seen = new HashSet<>();
        for (SectionKey key : delta.removedKeys()) {
            if (!seen.add(key)) throw new IOException("Duplicate removed section in delta: " + key);
            if (!SceneAssetCodec.sectionIntersectsBounds(delta.sourceBounds(), delta.haloBounds(),
                    key.relX(), key.relY(), key.relZ())) {
                throw new IOException("Removed section lies outside captured halo: " + key);
            }
        }
        for (SceneAssetCodec.SectionData section : delta.changedSections()) {
            SectionKey key = new SectionKey(section.relX, section.relY, section.relZ);
            if (!seen.add(key)) throw new IOException("Duplicate changed section in delta: " + key);
            if (!SceneAssetCodec.sectionIntersectsBounds(delta.sourceBounds(), delta.haloBounds(),
                    section.relX, section.relY, section.relZ)) {
                throw new IOException("Changed section lies outside captured halo: " + key);
            }
            if (section.palette.isEmpty() || section.palette.size() > 4096) {
                throw new IOException("Invalid palette size in delta section " + key);
            }
            for (String entry : section.palette) {
                SceneAssetCodec.requireStringLength("palette entry", entry, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            }
            if (!SceneAssetCodec.AIR_BLOCK_ID.equals(section.palette.get(0))) {
                throw new IOException("Delta requires palette slot 0 to be air in section " + key);
            }
            if (section.blockIndices.length != 4096
                    || section.skyLight.length != 2048 || section.blockLight.length != 2048) {
                throw new IOException("Invalid section array lengths at " + key);
            }
            for (short paletteIndex : section.blockIndices) {
                if (paletteIndex < 0 || paletteIndex >= section.palette.size()) {
                    throw new IOException("Invalid palette index " + paletteIndex + " in delta section " + key);
                }
            }
        }
    }

    /** 把"哪些 Section 原样沿用 base"打成位图（MSB first，与项目其它位序一致）。 */
    private static byte[] packRetainedBitmap(DeltaData delta) {
        int count = delta.targetSectionCount();
        byte[] bitmap = new byte[(count + 7) / 8];
        int retained = 0;
        for (int i = 0; i < count; i++) {
            if (delta.isRetained(i)) {
                bitmap[i >> 3] |= (byte) (1 << (i & 7));
                retained++;
            }
        }
        if (retained + delta.changedSections().size() != count) {
            throw new IllegalStateException("Scene delta retained/changed split does not match target section count");
        }
        return bitmap;
    }

    private static boolean[] unpackRetainedBitmap(byte[] bitmap, int count) {
        boolean[] flags = new boolean[count];
        for (int i = 0; i < count; i++) {
            flags[i] = (bitmap[i >> 3] & (1 << (i & 7))) != 0;
        }
        return flags;
    }

    private static void writeBounds(DataOutputStream dos, SceneBounds bounds) throws IOException {
        dos.writeInt(bounds.minX());
        dos.writeInt(bounds.minY());
        dos.writeInt(bounds.minZ());
        dos.writeInt(bounds.maxX());
        dos.writeInt(bounds.maxY());
        dos.writeInt(bounds.maxZ());
    }

    private static SceneBounds readBounds(DataInputStream dis, String label) throws IOException {
        int minX = dis.readInt();
        int minY = dis.readInt();
        int minZ = dis.readInt();
        int maxX = dis.readInt();
        int maxY = dis.readInt();
        int maxZ = dis.readInt();
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IOException("Scene delta " + label + " bounds are reversed");
        }
        return new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Section 记录：与 v3 的索引编码同源（位打包 / 非空气位图选择器），调色板在补丁里内联。 */
    private static void writeSection(DataOutputStream dos, CRC32 crc, byte[] scratch,
                                     SceneAssetCodec.SectionData section) throws IOException {
        SceneAssetCodec.writeIntCrc(dos, crc, scratch, section.relX);
        SceneAssetCodec.writeIntCrc(dos, crc, scratch, section.relY);
        SceneAssetCodec.writeIntCrc(dos, crc, scratch, section.relZ);

        int paletteSize = section.palette.size();
        SceneAssetCodec.writeIntCrc(dos, crc, scratch, paletteSize);
        for (String entry : section.palette) {
            // 调色板字符串不进流内 CRC：writeUTF 是 modified UTF-8，与 v3 同口径；
            // 整流已由 gzip trailer 的 CRC 覆盖。
            dos.writeUTF(entry);
        }

        int width = SceneAssetCodec.indexBitWidth(paletteSize);
        boolean sparse = SceneAssetCodec.useSparseEncoding(section.blockIndices, width);
        int encoding = sparse ? SceneAssetCodec.INDEX_ENCODING_SPARSE : SceneAssetCodec.INDEX_ENCODING_PACKED;
        dos.writeByte(encoding);
        crc.update(encoding);
        SceneAssetCodec.writeIndices(dos, crc, section.blockIndices, width, sparse);

        dos.write(section.skyLight);
        crc.update(section.skyLight);
        dos.write(section.blockLight);
        crc.update(section.blockLight);
    }

    /**
     * 解码补丁。校验强度与 v3 资产解码一致：先卡上限再分配、坐标必须落在 halo 内、键不得重复、
     * CRC 必须吻合、末尾不得有 trailing data。
     */
    public static DeltaData decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PATCH_BYTES) {
            throw new IOException("Invalid scene delta size");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             InputStream bounded = new SceneAssetCodec.BoundedInputStream(gzip, MAX_PATCH_UNCOMPRESSED_BYTES);
             DataInputStream dis = new DataInputStream(bounded)) {

            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid scene delta magic: " + Integer.toHexString(magic));
            }
            int patchVersion = dis.readInt();
            if (patchVersion != PATCH_VERSION) {
                throw new IOException("Unsupported scene delta version: " + patchVersion);
            }
            String baseSha256 = dis.readUTF();
            String targetSha256 = dis.readUTF();
            if (!baseSha256.matches(HASH_PATTERN) || !targetSha256.matches(HASH_PATTERN)) {
                throw new IOException("Scene delta has invalid asset hashes");
            }
            int dataVersion = dis.readInt();
            String dimensionId = dis.readUTF();
            SceneAssetCodec.requireStringLength("dimension id", dimensionId, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            if (ResourceLocation.tryParse(dimensionId) == null) {
                throw new IOException("Invalid scene delta dimension id: " + dimensionId);
            }
            SceneBounds sourceBounds = readBounds(dis, "source");
            SceneBounds haloBounds = readBounds(dis, "halo");
            SceneAssetCodec.validateBounds(sourceBounds, haloBounds);
            String fingerprint = dis.readUTF();
            SceneAssetCodec.requireStringLength("registry fingerprint", fingerprint,
                    SceneLimits.MAX_PAYLOAD_STRING_LENGTH);

            int targetSectionCount = dis.readInt();
            if (targetSectionCount < 0 || targetSectionCount > SceneLimits.MAX_DECODE_SECTIONS) {
                throw new IOException("Invalid delta target section count: " + targetSectionCount);
            }

            CRC32 crc = new CRC32();
            byte[] scratch = new byte[4];
            Set<SectionKey> seen = new HashSet<>();

            byte[] retainedBitmap = new byte[(targetSectionCount + 7) / 8];
            dis.readFully(retainedBitmap);
            crc.update(retainedBitmap);
            boolean[] retainedFlags = unpackRetainedBitmap(retainedBitmap, targetSectionCount);

            int removedCount = dis.readInt();
            if (removedCount < 0 || removedCount > SceneLimits.MAX_DECODE_SECTIONS) {
                throw new IOException("Invalid delta removed count: " + removedCount);
            }
            List<SectionKey> removed = new ArrayList<>(removedCount);
            for (int i = 0; i < removedCount; i++) {
                int rx = SceneAssetCodec.readIntCrc(dis, crc, scratch);
                int ry = SceneAssetCodec.readIntCrc(dis, crc, scratch);
                int rz = SceneAssetCodec.readIntCrc(dis, crc, scratch);
                if (!SceneAssetCodec.sectionIntersectsBounds(sourceBounds, haloBounds, rx, ry, rz)) {
                    throw new IOException("Removed section lies outside captured halo: " + rx + "," + ry + "," + rz);
                }
                SectionKey key = new SectionKey(rx, ry, rz);
                if (!seen.add(key)) {
                    throw new IOException("Duplicate section coordinates in delta: " + rx + "," + ry + "," + rz);
                }
                removed.add(key);
            }

            int changedCount = dis.readInt();
            if (changedCount < 0 || changedCount > SceneLimits.MAX_DECODE_SECTIONS
                    || removedCount + changedCount > SceneLimits.MAX_DECODE_SECTIONS) {
                throw new IOException("Invalid delta changed count: " + changedCount);
            }
            List<SceneAssetCodec.SectionData> changed = new ArrayList<>(changedCount);
            for (int i = 0; i < changedCount; i++) {
                changed.add(readSection(dis, crc, scratch, seen, sourceBounds, haloBounds, i));
            }

            long expectedCrc = dis.readLong();
            if (crc.getValue() != expectedCrc) {
                throw new IOException("Scene delta CRC mismatch: calculated=" + crc.getValue()
                        + ", expected=" + expectedCrc);
            }
            if (dis.read() != -1) {
                throw new IOException("Trailing data after scene delta CRC");
            }

            sortKeys(removed);
            int retainedCount = 0;
            for (boolean retained : retainedFlags) {
                if (retained) retainedCount++;
            }
            if (retainedCount + changedCount != targetSectionCount) {
                throw new IOException("Scene delta retained/changed split does not match target section count");
            }
            return new DeltaData(baseSha256, targetSha256, dataVersion, dimensionId,
                    sourceBounds, haloBounds, fingerprint, targetSectionCount, removed, changed,
                    retainedFlags);
        }
    }

    private static SceneAssetCodec.SectionData readSection(DataInputStream dis, CRC32 crc, byte[] scratch,
                                                           Set<SectionKey> seen, SceneBounds sourceBounds,
                                                           SceneBounds haloBounds, int index) throws IOException {
        int rx = SceneAssetCodec.readIntCrc(dis, crc, scratch);
        int ry = SceneAssetCodec.readIntCrc(dis, crc, scratch);
        int rz = SceneAssetCodec.readIntCrc(dis, crc, scratch);
        if (!SceneAssetCodec.sectionIntersectsBounds(sourceBounds, haloBounds, rx, ry, rz)) {
            throw new IOException("Changed section lies outside captured halo: " + rx + "," + ry + "," + rz);
        }
        SectionKey key = new SectionKey(rx, ry, rz);
        if (!seen.add(key)) {
            throw new IOException("Duplicate section coordinates in delta: " + rx + "," + ry + "," + rz);
        }

        int paletteSize = SceneAssetCodec.readIntCrc(dis, crc, scratch);
        if (paletteSize <= 0 || paletteSize > 4096) {
            throw new IOException("Invalid palette size in delta section " + index + ": " + paletteSize);
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int p = 0; p < paletteSize; p++) {
            String entry = dis.readUTF();
            SceneAssetCodec.requireStringLength("palette entry", entry, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            palette.add(entry);
        }
        if (!SceneAssetCodec.AIR_BLOCK_ID.equals(palette.get(0))) {
            // 与 v3 同一条硬性要求：位图/稀疏编码把索引 0 当作隐含默认值。
            throw new IOException("Delta requires palette slot 0 to be air in section " + key);
        }

        int encoding = dis.readByte() & 0xFF;
        crc.update(encoding);
        if (encoding != SceneAssetCodec.INDEX_ENCODING_PACKED
                && encoding != SceneAssetCodec.INDEX_ENCODING_SPARSE) {
            throw new IOException("Unknown delta index encoding: " + encoding);
        }
        short[] indices = new short[4096];
        SceneAssetCodec.readIndices(dis, crc, indices, SceneAssetCodec.indexBitWidth(paletteSize),
                encoding == SceneAssetCodec.INDEX_ENCODING_SPARSE, paletteSize, index);

        byte[] skyLight = new byte[2048];
        dis.readFully(skyLight);
        crc.update(skyLight);
        byte[] blockLight = new byte[2048];
        dis.readFully(blockLight);
        crc.update(blockLight);

        return new SceneAssetCodec.SectionData(rx, ry, rz, palette, indices, skyLight, blockLight);
    }

    /** 供日志/诊断用的短描述。 */
    public static String describe(DeltaData delta) {
        if (delta == null) return "无补丁";
        return "补丁[base=" + shortHash(delta.baseSha256()) + " → target=" + shortHash(delta.targetSha256())
                + ", 删除 " + delta.removedKeys().size() + " / 变更 " + delta.changedSections().size()
                + " / 共 " + delta.targetSectionCount() + " Section]";
    }

    private static String shortHash(String hash) {
        return hash.length() >= 12 ? hash.substring(0, 12) : hash;
    }
}
