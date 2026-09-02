package com.habitrain.core.scene.asset;

import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.SceneLimits;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * .hscene 场景几何资产格式编解码器。
 */
public final class SceneAssetCodec {
    public static final int MAGIC = 0x4853434E; // "HSCN"
    public static final int FORMAT_VERSION = 1;
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
        public final String fingerprint;
        public final List<SectionData> sections;

        public AssetData(int dataVersion, String dimensionId, SceneBounds sourceBounds,
                         String fingerprint, List<SectionData> sections) {
            this.formatVersion = FORMAT_VERSION;
            this.dataVersion = dataVersion;
            this.dimensionId = dimensionId != null ? dimensionId : "minecraft:overworld";
            this.sourceBounds = sourceBounds != null ? sourceBounds : SceneBounds.EMPTY;
            this.fingerprint = fingerprint != null ? fingerprint : "";
            this.sections = sections != null ? sections : new ArrayList<>();
        }
    }

    /**
     * 将 AssetData 编码为 GZIP 压缩的字节数组。
     */
    public static byte[] encode(AssetData data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             DataOutputStream dos = new DataOutputStream(gzos)) {

            dos.writeInt(MAGIC);
            dos.writeInt(FORMAT_VERSION);
            dos.writeInt(data.dataVersion);
            dos.writeUTF(data.dimensionId);

            dos.writeInt(data.sourceBounds.minX());
            dos.writeInt(data.sourceBounds.minY());
            dos.writeInt(data.sourceBounds.minZ());
            dos.writeInt(data.sourceBounds.maxX());
            dos.writeInt(data.sourceBounds.maxY());
            dos.writeInt(data.sourceBounds.maxZ());

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

            dos.writeLong(crc.getValue());
            dos.flush();
        }
        return baos.toByteArray();
    }

    /**
     * 从压缩的字节数组中解码 AssetData。
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
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported scene format version: " + version);
            }
            int dataVersion = dis.readInt();
            String dimensionId = dis.readUTF();

            int minX = dis.readInt();
            int minY = dis.readInt();
            int minZ = dis.readInt();
            int maxX = dis.readInt();
            int maxY = dis.readInt();
            int maxZ = dis.readInt();
            SceneBounds bounds = new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);

            String fingerprint = dis.readUTF();
            int sectionCount = dis.readInt();
            if (sectionCount < 0 || sectionCount > SceneLimits.MAX_DECODE_SECTIONS) {
                throw new IOException("Invalid section count: " + sectionCount);
            }

            List<SectionData> sections = new ArrayList<>(sectionCount);
            CRC32 crc = new CRC32();

            for (int s = 0; s < sectionCount; s++) {
                int rx = dis.readInt();
                int ry = dis.readInt();
                int rz = dis.readInt();

                int paletteSize = dis.readInt();
                if (paletteSize < 0 || paletteSize > 4096) {
                    throw new IOException("Invalid palette size: " + paletteSize);
                }
                List<String> palette = new ArrayList<>(paletteSize);
                for (int p = 0; p < paletteSize; p++) {
                    palette.add(dis.readUTF());
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

            long expectedCrc = dis.readLong();
            if (crc.getValue() != expectedCrc) {
                throw new IOException("Scene asset CRC mismatch: calculated=" + crc.getValue() + ", expected=" + expectedCrc);
            }

            return new AssetData(dataVersion, dimensionId, bounds, fingerprint, sections);
        }
    }

    /** Conservative encoded-size estimate used before compression/allocation. */
    public static long estimateUncompressedSize(AssetData data) {
        if (data == null) return 0L;
        long size = 128L;
        for (SectionData section : data.sections) {
            size += 16L + 8192L + 2048L + 2048L;
            for (String entry : section.palette) {
                size += 2L + (entry == null ? 0L : entry.getBytes(StandardCharsets.UTF_8).length);
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
