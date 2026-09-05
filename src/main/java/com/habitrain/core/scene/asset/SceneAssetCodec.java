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
 * .hscene 场景几何资产格式编解码器（支持 v1 读取与 v2 读写）。
 */
public final class SceneAssetCodec {
    public static final int MAGIC = 0x4853434E; // "HSCN"
    public static final int FORMAT_VERSION_V1 = 1;
    public static final int FORMAT_VERSION_V2 = 2;
    public static final int FORMAT_VERSION = FORMAT_VERSION_V2;

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
        if (data == null) throw new IllegalArgumentException("AssetData cannot be null");
        validateAssetData(data, formatVersion);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
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
            for (SectionData section : data.sections) {
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

    /**
     * 从压缩的字节数组中解码 AssetData（支持 v1 和 v2）。
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
            if (version != FORMAT_VERSION_V1 && version != FORMAT_VERSION_V2) {
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

            List<SectionData> sections = new ArrayList<>(sectionCount);
            Set<String> sectionPositions = new HashSet<>();
            CRC32 crc = new CRC32();

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

    private static void requireStringLength(String label, String value, int maximum) throws IOException {
        if (value == null || value.length() > maximum) {
            throw new IOException("Invalid " + label + " length: " + (value == null ? -1 : value.length()));
        }
    }

    private static void validateAssetData(AssetData data, int formatVersion) throws IOException {
        if (formatVersion != FORMAT_VERSION_V1 && formatVersion != FORMAT_VERSION_V2) {
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

    private static void validateBounds(SceneBounds source, SceneBounds halo) throws IOException {
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

    private static boolean sectionIntersectsBounds(SceneBounds source, SceneBounds halo,
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

    /** Conservative encoded-size estimate used before compression/allocation. */
    public static long estimateUncompressedSize(AssetData data) {
        if (data == null) return 0L;
        long size = 128L + 24L; // base header + halo bounds
        for (SectionData section : data.sections) {
            size += 16L + 8192L + 2048L + 2048L;
            for (String entry : section.palette) {
                size += 2L + (entry == null ? 0L : entry.getBytes(StandardCharsets.UTF_8).length);
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

    private static final class BoundedInputStream extends FilterInputStream {
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
