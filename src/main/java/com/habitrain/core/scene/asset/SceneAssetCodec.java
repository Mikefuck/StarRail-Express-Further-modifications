package com.habitrain.core.scene.asset;

import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * .hscene 场景几何资产格式编解码器（支持 v1/v2 读取与 v2/v3 写入）。
 *
 * <p>v3 是针对真实资产实测出来的紧凑编码（16 个线上资产、deflate 等级 6 下省约 15%），
 * 三处改动叠加：索引按调色板规模位打包、调色板字符串提到资产级全局表、光照数组统一挪到
 * 几何段之后。v2 的写路径保留下来给基准器做对照，v1/v2 的读路径不变——已发布资产与
 * 客户端磁盘缓存里的旧文件必须继续能解。</p>
 */
public final class SceneAssetCodec {
    public static final int MAGIC = 0x4853434E; // "HSCN"
    public static final int FORMAT_VERSION_V1 = 1;
    public static final int FORMAT_VERSION_V2 = 2;
    public static final int FORMAT_VERSION_V3 = 3;
    public static final int FORMAT_VERSION = FORMAT_VERSION_V3;

    public static final int MAX_COMPRESSED_BYTES = 64 * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L;

    private SceneAssetCodec() {}

    /**
     * 单个 16x16x16 section 的资产快照。
     */
    public static final class SectionData {
        public final int relX;
        public final int relY;
        public final int relZ;
        public final List<String> palette;
        public final short[] blockIndices; // 4096
        public final byte[] skyLight;      // 2048
        public final byte[] blockLight;    // 2048

        public SectionData(int relX, int relY, int relZ, List<String> palette,
                           short[] blockIndices, byte[] skyLight, byte[] blockLight) {
            this.relX = relX;
            this.relY = relY;
            this.relZ = relZ;
            this.palette = palette != null ? palette : new ArrayList<>();
            this.blockIndices = blockIndices != null ? blockIndices : new short[4096];
            this.skyLight = skyLight != null ? skyLight : new byte[2048];
            this.blockLight = blockLight != null ? blockLight : new byte[2048];
        }
    }

    /**
     * 完整场景资产的未压缩内存模型。
     */
    public static final class AssetData {
        public final int formatVersion;
        public final int dataVersion;
        public final String dimensionId;
        public final SceneBounds sourceBounds;
        public final SceneBounds haloBounds;
        public final String fingerprint;
        public final List<SectionData> sections;
        public final List<SceneBlockPayloadEntry> blockPayloads;

        public AssetData(int dataVersion, String dimensionId, SceneBounds sourceBounds,
                         String fingerprint, List<SectionData> sections) {
            this(FORMAT_VERSION, dataVersion, dimensionId, sourceBounds, sourceBounds, fingerprint, sections, Collections.emptyList());
        }

        public AssetData(int formatVersion, int dataVersion, String dimensionId, SceneBounds sourceBounds,
                         SceneBounds haloBounds, String fingerprint, List<SectionData> sections,
                         List<SceneBlockPayloadEntry> blockPayloads) {
            this.formatVersion = formatVersion;
            this.dataVersion = dataVersion;
            this.dimensionId = dimensionId != null ? dimensionId : "minecraft:overworld";
            this.sourceBounds = sourceBounds != null ? sourceBounds : SceneBounds.EMPTY;
            this.haloBounds = haloBounds != null ? haloBounds : (this.sourceBounds != null ? this.sourceBounds : SceneBounds.EMPTY);
            this.fingerprint = fingerprint != null ? fingerprint : "";
            this.sections = sections != null ? sections : new ArrayList<>();
            this.blockPayloads = blockPayloads != null ? blockPayloads : new ArrayList<>();
        }
    }

    /**
     * 将 AssetData 编码为 GZIP 压缩的字节数组（默认当前最高版本 FORMAT_VERSION）。
     */
    public static byte[] encode(AssetData data) throws IOException {
        return encode(data, data != null && data.formatVersion > 0 ? data.formatVersion : FORMAT_VERSION);
    }

    /**
     * 将 AssetData 编码为指定版本的 GZIP 压缩字节数组。
     */
    public static byte[] encode(AssetData data, int formatVersion) throws IOException {
        return encode(data, formatVersion, DEFAULT_GZIP_LEVEL);
    }

    /**
     * 将 AssetData 编码为指定版本、指定压缩级别的 GZIP 字节数组。
     *
     * <p>级别只给基准器用来量"更高等级省多少、多花多少时间"；线上发布固定走
     * {@link #DEFAULT_GZIP_LEVEL}。</p>
     */
    public static byte[] encode(AssetData data, int formatVersion, int gzipLevel) throws IOException {
        if (data == null) throw new IllegalArgumentException("AssetData cannot be null");
        validateAssetData(data, formatVersion);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new LeveledGzipOutputStream(baos, gzipLevel);
             DataOutputStream dos = new DataOutputStream(gzos)) {

            dos.writeInt(MAGIC);
            dos.writeInt(formatVersion);
            dos.writeInt(data.dataVersion);
            dos.writeUTF(data.dimensionId);

            dos.writeInt(data.sourceBounds.minX());
            dos.writeInt(data.sourceBounds.minY());
            dos.writeInt(data.sourceBounds.minZ());
            dos.writeInt(data.sourceBounds.maxX());
            dos.writeInt(data.sourceBounds.maxY());
            dos.writeInt(data.sourceBounds.maxZ());

            if (formatVersion >= FORMAT_VERSION_V2) {
                SceneBounds halo = data.haloBounds != null ? data.haloBounds : data.sourceBounds;
                dos.writeInt(halo.minX());
                dos.writeInt(halo.minY());
                dos.writeInt(halo.minZ());
                dos.writeInt(halo.maxX());
                dos.writeInt(halo.maxY());
                dos.writeInt(halo.maxZ());
            }

            dos.writeUTF(data.fingerprint);
            dos.writeInt(data.sections.size());

            CRC32 crc = new CRC32();
            if (formatVersion >= FORMAT_VERSION_V3) {
                writeSectionsV3(dos, crc, data.sections);
            } else {
                writeSectionsV2(dos, crc, data.sections);
            }

            if (formatVersion >= FORMAT_VERSION_V2) {
                List<SceneBlockPayloadEntry> payloads = data.blockPayloads != null ? data.blockPayloads : Collections.emptyList();
                dos.writeInt(payloads.size());
                for (SceneBlockPayloadEntry entry : payloads) {
                    dos.writeInt(entry.localX());
                    dos.writeInt(entry.localY());
                    dos.writeInt(entry.localZ());
                    dos.writeUTF(entry.adapterId().toString());
                    dos.writeInt(entry.adapterVersion());
                    byte[] payloadBytes = entry.payload().toByteArray();
                    dos.writeInt(payloadBytes.length);
                    dos.write(payloadBytes);
                    crc.update(payloadBytes);
                }
            }

            dos.writeLong(crc.getValue());
            dos.flush();
        }
        return baos.toByteArray();
    }

    /** v3 索引段的两种写法，写在每个 section 的索引数组之前的一个字节里。 */
    public static final int INDEX_ENCODING_PACKED = 0;
    public static final int INDEX_ENCODING_SPARSE = 1;

    /** 捕获路径预置在调色板第 0 位的方块；v3 的稀疏编码把索引 0 当隐含默认值。 */
    public static final String AIR_BLOCK_ID = "minecraft:air";

    /**
     * 发布默认压缩级别。
     *
     * <p>6 与历史行为完全一致：{@code new GZIPOutputStream(out)} 内部是
     * {@code Deflater.DEFAULT_COMPRESSION == -1}，解出来就是 6，所以默认级别下 v2 的输出
     * 与改动前逐字节相同（已发布资产的哈希不会变）。</p>
     */
    public static final int DEFAULT_GZIP_LEVEL = 6;
    public static final int MIN_GZIP_LEVEL = 1;
    public static final int MAX_GZIP_LEVEL = 9;

    /**
     * v2 的 section 段：坐标 + 每个 section 自带整份调色板字符串 + 4096 个 short 索引 + 两种光照。
     *
     * <p>保留这条写路径是为了让基准器能拿同一个 {@code AssetData} 出两份字节做对照；
     * 线上发布只走 v3。</p>
     */
    private static void writeSectionsV2(DataOutputStream dos, CRC32 crc, List<SectionData> sections)
            throws IOException {
        for (SectionData section : sections) {
            dos.writeInt(section.relX);
            dos.writeInt(section.relY);
            dos.writeInt(section.relZ);

            dos.writeInt(section.palette.size());
            for (String entry : section.palette) {
                dos.writeUTF(entry);
            }

            for (int i = 0; i < 4096; i++) {
                short idx = (i < section.blockIndices.length) ? section.blockIndices[i] : 0;
                dos.writeShort(idx);
            }

            byte[] sky = section.skyLight.length == 2048 ? section.skyLight : new byte[2048];
            dos.write(sky);
            crc.update(sky);

            byte[] block = section.blockLight.length == 2048 ? section.blockLight : new byte[2048];
            dos.write(block);
            crc.update(block);
        }
    }

    /**
     * v3 的 section 段：资产级全局调色板 → 几何趟（坐标 + 调色板引用 + 索引）→ 光照趟。
     *
     * <p>三处相对 v2 的改动都是实测出来的（16 个线上资产、deflate 等级 6 合计省约 15%）：</p>
     * <ul>
     *   <li><b>全局调色板</b>：v2 每个 section 都要重写一遍相同的方块串，8192 个 section 就是几千次
     *       重复；提到资产级一张表、section 里只留 int 引用。</li>
     *   <li><b>位打包</b>：4 项调色板也占 16 bit/格。改成按调色板规模取位宽，最坏 12 bit。</li>
     *   <li><b>光照趟后置</b>：同类数据聚在一起，deflate 能匹配到更长的重复片段。</li>
     * </ul>
     *
     * <p>调色板字符串本身不进流内 CRC：{@code writeUTF} 用的是 modified UTF-8，解码侧再转回
     * UTF-8 逐字节比对会在非 ASCII 上有分歧，而字符串本来就被 gzip trailer 的全流 CRC 覆盖。</p>
     */
    private static void writeSectionsV3(DataOutputStream dos, CRC32 crc, List<SectionData> sections)
            throws IOException {
        // LinkedHashMap 保证插入序 = section 遍历序，同一份 AssetData 每次编码得到完全相同的字节，
        // 这是"按 SHA-256 做内容寻址"的前提。
        Map<String, Integer> globalPalette = new LinkedHashMap<>();
        for (SectionData section : sections) {
            for (String entry : section.palette) {
                if (!globalPalette.containsKey(entry)) {
                    globalPalette.put(entry, globalPalette.size());
                }
            }
        }
        dos.writeInt(globalPalette.size());
        for (String entry : globalPalette.keySet()) {
            dos.writeUTF(entry);
        }

        byte[] scratch = new byte[4];
        for (SectionData section : sections) {
            writeIntCrc(dos, crc, scratch, section.relX);
            writeIntCrc(dos, crc, scratch, section.relY);
            writeIntCrc(dos, crc, scratch, section.relZ);

            int paletteSize = section.palette.size();
            writeIntCrc(dos, crc, scratch, paletteSize);
            for (String entry : section.palette) {
                writeIntCrc(dos, crc, scratch, globalPalette.get(entry));
            }

            int width = indexBitWidth(paletteSize);
            boolean sparse = useSparseEncoding(section.blockIndices, width);
            int encoding = sparse ? INDEX_ENCODING_SPARSE : INDEX_ENCODING_PACKED;
            dos.writeByte(encoding);
            crc.update(encoding);
            writeIndices(dos, crc, section.blockIndices, width, sparse);
        }

        for (SectionData section : sections) {
            byte[] sky = section.skyLight.length == 2048 ? section.skyLight : new byte[2048];
            dos.write(sky);
            crc.update(sky);

            byte[] block = section.blockLight.length == 2048 ? section.blockLight : new byte[2048];
            dos.write(block);
            crc.update(block);
        }
    }

    /**
     * 索引位宽阶梯。
     *
     * <p>必须是 1/2/4/8/12 这种整数阶：实测按 {@code log2} 取 5/6/7 bit 反而更大——非字节对齐的
     * 位流会让之后每一个字节都错位，deflate 再也匹配不上重复片段。</p>
     */
    static int indexBitWidth(int paletteSize) {
        if (paletteSize <= 1) return 0;
        if (paletteSize <= 2) return 1;
        if (paletteSize <= 4) return 2;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 256) return 8;
        return 12; // paletteSize 上限 4096，12 bit 装得下 0..4095
    }

    /**
     * 按解析式在"位打包"与"非空气位图"之间二选一，不做试编码。
     *
     * <p>稀疏位图对空气占比高的 section 更省，但对密实 section 反而更费（实测 5% 空气的密集场景
     * 下多出约 2.4%）。两种写法的字节数都能直接算出来，所以这里按公式取小者，保证任何形状的
     * section 都不会因为选了稀疏而变大——代价只有每 section 一个字节的编码标记。</p>
     */
    static boolean useSparseEncoding(short[] indices, int width) {
        if (width == 0) {
            return false; // 索引全 0：打包后一个字节都不占，位图反而要 512 字节
        }
        int nonZero = 0;
        int limit = Math.min(indices.length, 4096);
        for (int i = 0; i < limit; i++) {
            if (indices[i] != 0) nonZero++;
        }
        long packedBytes = (4096L * width + 7L) / 8L;
        long sparseBytes = 512L + (nonZero * (long) width + 7L) / 8L;
        return sparseBytes < packedBytes;
    }

    /**
     * 写索引段。稀疏模式下先写 512 字节非空气位图，之后只为置位的格子写值。
     *
     * <p>位序是 MSB first：与 {@code DataOutputStream} 的其余字段一致，解码侧按同样的顺序回读。</p>
     */
    static void writeIndices(DataOutputStream dos, CRC32 crc, short[] indices,
                             int width, boolean sparse) throws IOException {
        if (width == 0) {
            return; // paletteSize <= 1：所有索引都是 0，一个字节都不用写
        }
        if (sparse) {
            byte[] bitmap = new byte[512];
            for (int i = 0; i < 4096; i++) {
                int value = (i < indices.length) ? (indices[i] & 0xFFFF) : 0;
                if (value != 0) {
                    bitmap[i >> 3] |= (byte) (1 << (i & 7));
                }
            }
            dos.write(bitmap);
            crc.update(bitmap);
        }

        long acc = 0L;
        int bits = 0;
        for (int i = 0; i < 4096; i++) {
            int value = (i < indices.length) ? (indices[i] & 0xFFFF) : 0;
            if (sparse && value == 0) {
                continue; // 位图里没有它，解码侧会自动补 0
            }
            acc = (acc << width) | value;
            bits += width;
            while (bits >= 8) {
                bits -= 8;
                int b = (int) ((acc >>> bits) & 0xFF);
                dos.writeByte(b);
                crc.update(b);
            }
        }
        if (bits > 0) {
            int b = (int) ((acc << (8 - bits)) & 0xFF);
            dos.writeByte(b);
            crc.update(b);
        }
    }

    /** 写一个 int 并同步喂给流内 CRC；scratch 复用避免为每个字段分配数组。 */
    static void writeIntCrc(DataOutputStream dos, CRC32 crc, byte[] scratch, int value)
            throws IOException {
        dos.writeInt(value);
        scratch[0] = (byte) (value >>> 24);
        scratch[1] = (byte) (value >>> 16);
        scratch[2] = (byte) (value >>> 8);
        scratch[3] = (byte) value;
        crc.update(scratch, 0, 4);
    }

    /**
     * 从压缩的字节数组中解码 AssetData（支持 v1、v2 与 v3）。
     */
    public static AssetData decode(byte[] compressedBytes) throws IOException {
        if (compressedBytes == null || compressedBytes.length == 0
                || compressedBytes.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid compressed scene asset size");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             InputStream gzis = new BoundedInputStream(gzip, MAX_UNCOMPRESSED_BYTES);
             DataInputStream dis = new DataInputStream(gzis)) {

            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid scene asset magic: " + Integer.toHexString(magic));
            }
            int version = dis.readInt();
            if (version != FORMAT_VERSION_V1 && version != FORMAT_VERSION_V2 && version != FORMAT_VERSION_V3) {
                throw new IOException("Unsupported scene format version: " + version);
            }
            int dataVersion = dis.readInt();
            String dimensionId = dis.readUTF();
            requireStringLength("dimension id", dimensionId, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);

            int minX = dis.readInt();
            int minY = dis.readInt();
            int minZ = dis.readInt();
            int maxX = dis.readInt();
            int maxY = dis.readInt();
            int maxZ = dis.readInt();
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IOException("Scene source bounds are reversed");
            }
            SceneBounds sourceBounds = new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);

            SceneBounds haloBounds = sourceBounds;
            if (version >= FORMAT_VERSION_V2) {
                int hMinX = dis.readInt();
                int hMinY = dis.readInt();
                int hMinZ = dis.readInt();
                int hMaxX = dis.readInt();
                int hMaxY = dis.readInt();
                int hMaxZ = dis.readInt();
                if (hMinX > hMaxX || hMinY > hMaxY || hMinZ > hMaxZ) {
                    throw new IOException("Scene halo bounds are reversed");
                }
                haloBounds = new SceneBounds(hMinX, hMinY, hMinZ, hMaxX, hMaxY, hMaxZ);
            }
            validateBounds(sourceBounds, haloBounds);

            String fingerprint = dis.readUTF();
            requireStringLength("registry fingerprint", fingerprint, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            int sectionCount = dis.readInt();
            if (sectionCount < 0 || sectionCount > SceneLimits.MAX_DECODE_SECTIONS) {
                throw new IOException("Invalid section count: " + sectionCount);
            }

            CRC32 crc = new CRC32();
            List<SectionData> sections = version >= FORMAT_VERSION_V3
                    ? readSectionsV3(dis, sectionCount, sourceBounds, haloBounds, crc)
                    : readSectionsV2(dis, sectionCount, sourceBounds, haloBounds, crc);

            List<SceneBlockPayloadEntry> blockPayloads = new ArrayList<>();
            if (version >= FORMAT_VERSION_V2) {
                int payloadCount = dis.readInt();
                if (payloadCount < 0 || payloadCount > SceneLimits.MAX_NON_AIR_BLOCKS) {
                    throw new IOException("Invalid payload count: " + payloadCount);
                }
                long totalPayloadBytes = 0L;
                Set<String> payloadPositions = new HashSet<>();
                for (int i = 0; i < payloadCount; i++) {
                    int lx = dis.readInt();
                    int ly = dis.readInt();
                    int lz = dis.readInt();
                    if (lx < 0 || ly < 0 || lz < 0
                            || lx >= sourceBounds.sizeX() || ly >= sourceBounds.sizeY()
                            || lz >= sourceBounds.sizeZ()) {
                        throw new IOException("Block payload position lies outside source bounds: "
                                + lx + "," + ly + "," + lz);
                    }
                    if (!payloadPositions.add(lx + ":" + ly + ":" + lz)) {
                        throw new IOException("Duplicate block payload position: " + lx + "," + ly + "," + lz);
                    }
                    String adapterIdStr = dis.readUTF();
                    if (adapterIdStr.length() > SceneLimits.MAX_PAYLOAD_STRING_LENGTH) {
                        throw new IOException("Adapter ID exceeds maximum string length: " + adapterIdStr.length());
                    }
                    ResourceLocation adapterId = ResourceLocation.tryParse(adapterIdStr);
                    if (adapterId == null) {
                        throw new IOException("Invalid adapter ResourceLocation: " + adapterIdStr);
                    }
                    int adapterVersion = dis.readInt();
                    if (adapterVersion <= 0) {
                        throw new IOException("Invalid adapter data version: " + adapterVersion);
                    }
                    int byteLength = dis.readInt();
                    if (byteLength < 0 || byteLength > SceneLimits.MAX_BLOCK_PAYLOAD_BYTES) {
                        throw new IOException("Single block payload exceeds limit: " + byteLength + " bytes");
                    }
                    totalPayloadBytes += byteLength;
                    if (totalPayloadBytes > SceneLimits.MAX_TOTAL_PAYLOAD_BYTES) {
                        throw new IOException("Total asset adapter data exceeds 32 MiB limit: " + totalPayloadBytes + " bytes");
                    }
                    byte[] payloadBytes = new byte[byteLength];
                    dis.readFully(payloadBytes);
                    crc.update(payloadBytes);

                    SceneRenderPayload payload = SceneRenderPayload.fromByteArray(payloadBytes);
                    blockPayloads.add(new SceneBlockPayloadEntry(lx, ly, lz, adapterId, adapterVersion, payload));
                }
            }

            long expectedCrc = dis.readLong();
            if (crc.getValue() != expectedCrc) {
                throw new IOException("Scene asset CRC mismatch: calculated=" + crc.getValue() + ", expected=" + expectedCrc);
            }
            if (dis.read() != -1) {
                throw new IOException("Trailing data after scene asset CRC");
            }

            return new AssetData(version, dataVersion, dimensionId, sourceBounds, haloBounds, fingerprint, sections, blockPayloads);
        }
    }

    /**
     * v1/v2 的 section 段读取：布局与写入端逐字节对应，未做任何改动。
     *
     * <p>v2 的流内 CRC 只覆盖两种光照数组与载荷——这是既定事实，已发布的资产就是按这个口径
     * 算出来的，这里不能"顺手修正"，否则老文件会全部校验失败。</p>
     */
    private static List<SectionData> readSectionsV2(DataInputStream dis, int sectionCount,
                                                    SceneBounds sourceBounds, SceneBounds haloBounds,
                                                    CRC32 crc) throws IOException {
        List<SectionData> sections = new ArrayList<>(sectionCount);
        Set<String> sectionPositions = new HashSet<>();

        for (int s = 0; s < sectionCount; s++) {
            int rx = dis.readInt();
            int ry = dis.readInt();
            int rz = dis.readInt();
            if (!sectionIntersectsBounds(sourceBounds, haloBounds, rx, ry, rz)) {
                throw new IOException("Section lies outside captured halo: " + rx + "," + ry + "," + rz);
            }
            if (!sectionPositions.add(rx + ":" + ry + ":" + rz)) {
                throw new IOException("Duplicate section coordinates: " + rx + "," + ry + "," + rz);
            }

            int paletteSize = dis.readInt();
            if (paletteSize <= 0 || paletteSize > 4096) {
                throw new IOException("Invalid palette size: " + paletteSize);
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int p = 0; p < paletteSize; p++) {
                String paletteEntry = dis.readUTF();
                requireStringLength("palette entry", paletteEntry, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
                palette.add(paletteEntry);
            }

            short[] indices = new short[4096];
            for (int i = 0; i < 4096; i++) {
                indices[i] = dis.readShort();
                if (indices[i] < 0 || indices[i] >= paletteSize) {
                    throw new IOException("Invalid palette index " + indices[i] + " in section " + s);
                }
            }

            byte[] skyLight = new byte[2048];
            dis.readFully(skyLight);
            crc.update(skyLight);

            byte[] blockLight = new byte[2048];
            dis.readFully(blockLight);
            crc.update(blockLight);

            sections.add(new SectionData(rx, ry, rz, palette, indices, skyLight, blockLight));
        }
        return sections;
    }

    /**
     * v3 的 section 段读取：全局调色板 → 几何趟 → 光照趟。
     *
     * <p>光照必须在几何趟之后才能拿到，而 {@link SectionData} 的字段是 final，所以中间用
     * {@link V3SectionGeometry} 承一下，等两趟都读完再按原顺序拼出 {@code SectionData}。</p>
     */
    private static List<SectionData> readSectionsV3(DataInputStream dis, int sectionCount,
                                                    SceneBounds sourceBounds, SceneBounds haloBounds,
                                                    CRC32 crc) throws IOException {
        int globalCount = dis.readInt();
        // 这个上限必须在任何分配之前生效：否则一个伪造的超大 int 就能让客户端在读到任何数据
        // 之前先按它分配一张表。v2 的 sectionCount 与 paletteSize 有同样的前置检查。
        if (globalCount <= 0 || globalCount > SceneLimits.MAX_GLOBAL_PALETTE_ENTRIES) {
            throw new IOException("Invalid global palette size: " + globalCount);
        }
        List<String> globalPalette = new ArrayList<>(globalCount);
        for (int i = 0; i < globalCount; i++) {
            String entry = dis.readUTF();
            requireStringLength("global palette entry", entry, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            globalPalette.add(entry);
        }

        Set<String> sectionPositions = new HashSet<>();
        V3SectionGeometry[] geometry = new V3SectionGeometry[sectionCount];
        byte[] scratch = new byte[4];

        for (int s = 0; s < sectionCount; s++) {
            int rx = readIntCrc(dis, crc, scratch);
            int ry = readIntCrc(dis, crc, scratch);
            int rz = readIntCrc(dis, crc, scratch);
            if (!sectionIntersectsBounds(sourceBounds, haloBounds, rx, ry, rz)) {
                throw new IOException("Section lies outside captured halo: " + rx + "," + ry + "," + rz);
            }
            if (!sectionPositions.add(rx + ":" + ry + ":" + rz)) {
                throw new IOException("Duplicate section coordinates: " + rx + "," + ry + "," + rz);
            }

            int paletteSize = readIntCrc(dis, crc, scratch);
            if (paletteSize <= 0 || paletteSize > 4096) {
                throw new IOException("Invalid palette size: " + paletteSize);
            }
            List<String> palette = new ArrayList<>(paletteSize);
            for (int p = 0; p < paletteSize; p++) {
                int globalIndex = readIntCrc(dis, crc, scratch);
                if (globalIndex < 0 || globalIndex >= globalCount) {
                    throw new IOException("Global palette index out of range: " + globalIndex);
                }
                palette.add(globalPalette.get(globalIndex));
            }

            int encoding = dis.readByte() & 0xFF;
            crc.update(encoding);
            if (encoding != INDEX_ENCODING_PACKED && encoding != INDEX_ENCODING_SPARSE) {
                // 未知编码必须直接失败：静默按某个默认布局解会把"格式演进"变成"读到垃圾"。
                throw new IOException("Unknown v3 index encoding: " + encoding);
            }

            short[] indices = new short[4096];
            readIndices(dis, crc, indices, indexBitWidth(paletteSize),
                    encoding == INDEX_ENCODING_SPARSE, paletteSize, s);
            geometry[s] = new V3SectionGeometry(rx, ry, rz, palette, indices);
        }

        List<SectionData> sections = new ArrayList<>(sectionCount);
        for (int s = 0; s < sectionCount; s++) {
            byte[] skyLight = new byte[2048];
            dis.readFully(skyLight);
            crc.update(skyLight);

            byte[] blockLight = new byte[2048];
            dis.readFully(blockLight);
            crc.update(blockLight);

            V3SectionGeometry g = geometry[s];
            sections.add(new SectionData(g.relX, g.relY, g.relZ, g.palette, g.indices, skyLight, blockLight));
        }
        return sections;
    }

    /**
     * 读索引段，与 {@link #writeIndices} 严格互逆（MSB first）。
     *
     * <p>整块先按已知长度读进数组再解包：位宽 12 时跨字节取值，逐字节流式解会把自己绕晕，
     * 而长度可以由位图 popcount 精确算出，不需要边读边猜。</p>
     */
    static void readIndices(DataInputStream dis, CRC32 crc, short[] indices,
                            int width, boolean sparse, int paletteSize, int sectionIndex)
            throws IOException {
        if (width == 0) {
            return; // paletteSize <= 1：整段索引都是 0，流里没有这一段
        }
        int valueCount;
        int packedBytes;
        byte[] bitmap = null;
        if (sparse) {
            bitmap = new byte[512];
            dis.readFully(bitmap);
            crc.update(bitmap);
            int nonZero = 0;
            for (byte b : bitmap) {
                nonZero += Integer.bitCount(b & 0xFF);
            }
            valueCount = nonZero;
            packedBytes = (nonZero * width + 7) / 8;
        } else {
            valueCount = 4096;
            packedBytes = (4096 * width + 7) / 8;
        }

        byte[] packed = new byte[packedBytes];
        dis.readFully(packed);
        crc.update(packed);

        int bitPos = 0;
        int written = 0;
        for (int i = 0; i < 4096 && written < valueCount; i++) {
            if (sparse && (bitmap[i >> 3] & (1 << (i & 7))) == 0) {
                continue; // 位图没置位 → 该格是 0
            }
            int value = 0;
            for (int j = 0; j < width; j++, bitPos++) {
                if ((packed[bitPos >>> 3] & (1 << (7 - (bitPos & 7)))) != 0) {
                    value |= 1 << (width - 1 - j);
                }
            }
            if (value >= paletteSize) {
                throw new IOException("Invalid palette index " + value + " in section " + sectionIndex);
            }
            indices[i] = (short) value;
            written++;
        }
    }

    static int readIntCrc(DataInputStream dis, CRC32 crc, byte[] scratch) throws IOException {
        int value = dis.readInt();
        scratch[0] = (byte) (value >>> 24);
        scratch[1] = (byte) (value >>> 16);
        scratch[2] = (byte) (value >>> 8);
        scratch[3] = (byte) value;
        crc.update(scratch, 0, 4);
        return value;
    }

    /** v3 几何趟的中间载体：光照还没读到，先攒着坐标、调色板与索引。 */
    private static final class V3SectionGeometry {
        final int relX;
        final int relY;
        final int relZ;
        final List<String> palette;
        final short[] indices;

        V3SectionGeometry(int relX, int relY, int relZ, List<String> palette, short[] indices) {
            this.relX = relX;
            this.relY = relY;
            this.relZ = relZ;
            this.palette = palette;
            this.indices = indices;
        }
    }

    static void requireStringLength(String label, String value, int maximum) throws IOException {
        if (value == null || value.length() > maximum) {
            throw new IOException("Invalid " + label + " length: " + (value == null ? -1 : value.length()));
        }
    }

    private static void validateAssetData(AssetData data, int formatVersion) throws IOException {
        if (formatVersion != FORMAT_VERSION_V1 && formatVersion != FORMAT_VERSION_V2
                && formatVersion != FORMAT_VERSION_V3) {
            throw new IOException("Unsupported scene format version: " + formatVersion);
        }
        requireStringLength("dimension id", data.dimensionId, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
        if (ResourceLocation.tryParse(data.dimensionId) == null) {
            throw new IOException("Invalid scene dimension id: " + data.dimensionId);
        }
        requireStringLength("registry fingerprint", data.fingerprint, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
        SceneBounds halo = formatVersion >= FORMAT_VERSION_V2 ? data.haloBounds : data.sourceBounds;
        validateBounds(data.sourceBounds, halo);
        if (data.sections == null || data.sections.size() > SceneLimits.MAX_DECODE_SECTIONS) {
            throw new IOException("Invalid section count: " + (data.sections == null ? -1 : data.sections.size()));
        }

        Set<String> sectionPositions = new HashSet<>();
        for (SectionData section : data.sections) {
            if (section == null) throw new IOException("Scene asset contains a null section");
            String sectionKey = section.relX + ":" + section.relY + ":" + section.relZ;
            if (!sectionPositions.add(sectionKey)) {
                throw new IOException("Duplicate section coordinates: " + sectionKey);
            }
            if (!sectionIntersectsBounds(data.sourceBounds, halo, section.relX, section.relY, section.relZ)) {
                throw new IOException("Section lies outside captured halo: " + sectionKey);
            }
            if (section.palette == null || section.palette.isEmpty() || section.palette.size() > 4096) {
                throw new IOException("Invalid palette size in section " + sectionKey);
            }
            for (String paletteEntry : section.palette) {
                requireStringLength("palette entry", paletteEntry, SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            }
            if (formatVersion >= FORMAT_VERSION_V3 && !AIR_BLOCK_ID.equals(section.palette.get(0))) {
                // v3 的稀疏编码把"索引 0"当作隐含默认值（位图没置位的格子直接补 0），而渲染端
                // 本来就跳过索引 0。如果某个 section 的第 0 项不是空气，那些方块会被静默丢掉——
                // 宁可在发布时就报错。捕获路径本来就预置了 minecraft:air（SceneCaptureService），
                // 所以这条只在手工构造的资产上才会触发。
                throw new IOException("v3 requires palette slot 0 to be air in section " + sectionKey);
            }
            if (section.blockIndices == null || section.blockIndices.length != 4096
                    || section.skyLight == null || section.skyLight.length != 2048
                    || section.blockLight == null || section.blockLight.length != 2048) {
                throw new IOException("Invalid section array lengths at " + sectionKey);
            }
            for (short paletteIndex : section.blockIndices) {
                if (paletteIndex < 0 || paletteIndex >= section.palette.size()) {
                    throw new IOException("Invalid palette index " + paletteIndex + " in section " + sectionKey);
                }
            }
        }

        if (formatVersion < FORMAT_VERSION_V2) return;
        List<SceneBlockPayloadEntry> payloads = data.blockPayloads != null
                ? data.blockPayloads : Collections.emptyList();
        if (payloads.size() > SceneLimits.MAX_NON_AIR_BLOCKS) {
            throw new IOException("Invalid payload count: " + payloads.size());
        }
        long totalPayloadBytes = 0L;
        Set<String> payloadPositions = new HashSet<>();
        for (SceneBlockPayloadEntry entry : payloads) {
            if (entry == null || entry.adapterId() == null || entry.payload() == null) {
                throw new IOException("Scene asset contains an incomplete block payload entry");
            }
            if (entry.localX() < 0 || entry.localY() < 0 || entry.localZ() < 0
                    || entry.localX() >= data.sourceBounds.sizeX()
                    || entry.localY() >= data.sourceBounds.sizeY()
                    || entry.localZ() >= data.sourceBounds.sizeZ()) {
                throw new IOException("Block payload lies outside source bounds: "
                        + entry.localX() + "," + entry.localY() + "," + entry.localZ());
            }
            String payloadKey = entry.localX() + ":" + entry.localY() + ":" + entry.localZ();
            if (!payloadPositions.add(payloadKey)) {
                throw new IOException("Duplicate block payload coordinates: " + payloadKey);
            }
            requireStringLength("adapter id", entry.adapterId().toString(), SceneLimits.MAX_PAYLOAD_STRING_LENGTH);
            if (entry.adapterVersion() <= 0) {
                throw new IOException("Invalid adapter version at " + payloadKey + ": " + entry.adapterVersion());
            }
            entry.payload().validate();
            int payloadBytes = entry.payload().toByteArray().length;
            totalPayloadBytes += payloadBytes;
            if (payloadBytes > SceneLimits.MAX_BLOCK_PAYLOAD_BYTES
                    || totalPayloadBytes > SceneLimits.MAX_TOTAL_PAYLOAD_BYTES) {
                throw new IOException("Scene asset visual payload data exceeds its size limit");
            }
        }
    }

    static void validateBounds(SceneBounds source, SceneBounds halo) throws IOException {
        if (source == null || halo == null || source.isEmpty() || halo.isEmpty()) {
            throw new IOException("Scene source/halo bounds must be non-empty");
        }
        long sourceX = (long) source.maxX() - source.minX();
        long sourceY = (long) source.maxY() - source.minY();
        long sourceZ = (long) source.maxZ() - source.minZ();
        if (sourceX > SceneLimits.MAX_AXIS_LENGTH || sourceY > SceneLimits.MAX_AXIS_LENGTH
                || sourceZ > SceneLimits.MAX_AXIS_LENGTH) {
            throw new IOException("Scene source bounds exceed the per-axis limit");
        }
        long haloLimit = SceneLimits.MAX_AXIS_LENGTH + 2L;
        long haloX = (long) halo.maxX() - halo.minX();
        long haloY = (long) halo.maxY() - halo.minY();
        long haloZ = (long) halo.maxZ() - halo.minZ();
        if (haloX > haloLimit || haloY > haloLimit || haloZ > haloLimit
                || halo.minX() > source.minX() || halo.minY() > source.minY()
                || halo.minZ() > source.minZ() || halo.maxX() < source.maxX()
                || halo.maxY() < source.maxY() || halo.maxZ() < source.maxZ()) {
            throw new IOException("Scene halo bounds do not conservatively contain the source bounds");
        }
    }

    static boolean sectionIntersectsBounds(SceneBounds source, SceneBounds halo,
                                           int relX, int relY, int relZ) {
        long sectionX = (long) source.minSectionX() + relX;
        long sectionY = (long) source.minSectionY() + relY;
        long sectionZ = (long) source.minSectionZ() + relZ;
        long minX = sectionX * 16L;
        long minY = sectionY * 16L;
        long minZ = sectionZ * 16L;
        return minX < halo.maxX() && minX + 16L > halo.minX()
                && minY < halo.maxY() && minY + 16L > halo.minY()
                && minZ < halo.maxZ() && minZ + 16L > halo.minZ();
    }

    /** 编码后未压缩字节数的保守估算，按当前默认版本计算。 */
    public static long estimateUncompressedSize(AssetData data) {
        return estimateUncompressedSize(data, FORMAT_VERSION);
    }

    /**
     * 按指定版本估算编码后的未压缩字节数。
     *
     * <p>调用方是发布前的 128 MiB 门控与描述符里的 {@code uncompressedSize}，所以必须是上界：
     * v3 的索引块取"位打包"的长度——稀疏只在严格更小时才会被选中，因此打包长度同时是两种
     * 编码的上界。全局调色板按各 section 调色板之和计（去重后只会更小），同样偏保守。</p>
     */
    public static long estimateUncompressedSize(AssetData data, int formatVersion) {
        if (data == null) return 0L;
        long size = 128L + 24L; // base header + halo bounds
        if (formatVersion >= FORMAT_VERSION_V3) {
            size += 4L; // globalPaletteSize 字段
            for (SectionData section : data.sections) {
                int paletteSize = section.palette.size();
                for (String entry : section.palette) {
                    size += 2L + (entry == null ? 0L : entry.getBytes(StandardCharsets.UTF_8).length);
                }
                // 坐标 12 + paletteSize 4 + 全局索引引用 4/项 + 编码标记 1 + 索引块 + 两种光照
                size += 16L + 4L * paletteSize + 1L
                        + (4096L * indexBitWidth(paletteSize) + 7L) / 8L
                        + 2048L + 2048L;
            }
        } else {
            for (SectionData section : data.sections) {
                size += 16L + 8192L + 2048L + 2048L;
                for (String entry : section.palette) {
                    size += 2L + (entry == null ? 0L : entry.getBytes(StandardCharsets.UTF_8).length);
                }
            }
        }
        if (data.blockPayloads != null) {
            for (SceneBlockPayloadEntry entry : data.blockPayloads) {
                size += 32L; // position + adapterId overhead
                try {
                    size += entry.payload().toByteArray().length;
                } catch (Throwable ignored) {
                    size += 256L;
                }
            }
        }
        return size;
    }

    /**
     * 已解码资产驻留堆内存的保守估算，供客户端内存配额使用。
     *
     * <p>与 {@link #estimateUncompressedSize} 的区别是<b>不分配任何中间对象</b>：那个方法对每个
     * 调色板条目调用 {@code getBytes(UTF_8)}，在最大允许的资产上会产生十万量级的临时数组，
     * 而这里是在客户端线程上调用的。调色板条目是方块 ID（ASCII），用 {@code length() * 2}
     * 作为 UTF-8 字节数的上界既保守又不分配。</p>
     *
     * <p>估算覆盖：Section 对象头与三个数组（{@code short[4096]} + 两个 {@code byte[2048]}）、
     * 调色板字符串与列表槽位、以及每个视觉载荷序列化后的大小。它刻意偏保守——宁可早一点淘汰，
     * 也不要让实际占用超出配额。</p>
     */
    public static long estimateRetainedBytes(AssetData data) {
        if (data == null) return 0L;
        long size = 256L;
        if (data.sections != null) {
            for (SectionData section : data.sections) {
                // 56 = 对象头 + 三个字段；8192/2048/2048 是三个数组的裸数据。
                size += 56L + 8192L + 2048L + 2048L;
                if (section.palette != null) {
                    size += 40L; // List 槽位
                    for (String entry : section.palette) {
                        // 40 = String 对象头 + 引用槽位；ID 是 ASCII，长度即字节数。
                        size += 40L + (entry == null ? 0L : entry.length() * 2L);
                    }
                }
            }
        }
        if (data.blockPayloads != null) {
            for (SceneBlockPayloadEntry entry : data.blockPayloads) {
                size += 32L;
                try {
                    size += entry.payload().toByteArray().length;
                } catch (Throwable ignored) {
                    size += 256L;
                }
            }
        }
        return size;
    }

    /**
     * 可控压缩级别的 GZIP 输出流。
     *
     * <p>{@code DeflaterOutputStream.def} 是 protected，外部拿不到，只能靠子类把级别设进去。
     * 缓冲区仍传 512，与 {@code new GZIPOutputStream(out)} 等价——deflate 的输出只取决于
     * 级别与窗口，缓冲区只影响写入分块，所以默认级别下的字节与改动前完全一致。</p>
     */
    private static final class LeveledGzipOutputStream extends GZIPOutputStream {
        LeveledGzipOutputStream(OutputStream out, int level) throws IOException {
            super(out, 512);
            this.def.setLevel(Math.max(MIN_GZIP_LEVEL, Math.min(MAX_GZIP_LEVEL, level)));
        }
    }

    static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) add(1);
            return value;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) add(read);
            return read;
        }

        private void add(int amount) throws IOException {
            count += amount;
            if (count > limit) throw new IOException("Scene asset exceeds uncompressed size limit");
        }
    }

    /**
     * 计算字节数组的 SHA-256 十六进制哈希串。
     */
    public static String calculateSha256(byte[] data) {
        if (data == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 计算 CRC32。
     */
    public static long calculateCrc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }
}
