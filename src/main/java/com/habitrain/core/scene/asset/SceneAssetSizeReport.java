package com.habitrain.core.scene.asset;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 一份 v3 场景资产的体积归因与告警判定（MC 无关，可单测）。
 *
 * <p>存在的理由：报告里"真正决定字节数的是选区大小"这句话，操作者在游戏里看不到任何依据。
 * 发布时把"原始字节都花在哪了、压缩后多大、离告警线还有多远"写进日志与状态提示，选区该不该收窄
 * 才有据可依。</p>
 *
 * <p>各分项是按 v3 的线格式<b>解析式算出</b>的，不是二次编码量出来的。这条等式是被测试钉住的：
 * 六个分项之和必须等于真实解压后的长度（见 {@code SceneAssetSizeReportTest}）。</p>
 *
 * <p>只覆盖 v3：发布路径只会产出 v3，旧版本的归因没有消费方。</p>
 */
public final class SceneAssetSizeReport {

    /** 超过这个压缩体积就告警：约等于当前最大真实资产的 16 倍，够大才值得提醒。 */
    public static final long WARN_COMPRESSED_BYTES = 1024L * 1024L;
    /** Section 数告警线：硬上限 8192 的 1/8，也是当前最大真实资产的 16 倍。 */
    public static final int WARN_SECTIONS = 1024;
    /** 非空气方块告警线：硬上限 200 万的 1/5。 */
    public static final long WARN_NON_AIR_BLOCKS = 400_000L;

    /** 体积主要花在哪一块——决定告警给什么建议。 */
    public enum Part {
        PALETTE("调色板", "方块种类过多（含大量带状态方块），可考虑减少装饰方块种类"),
        INDEX("方块索引", "装饰过密：重复的装饰方块是索引体积的主要来源，建议减少装饰密度或缩小选区"),
        LIGHT("光照", "选区过大：空 Section 也要各带 4 KiB 光照数组，建议缩小选区"),
        PAYLOAD("视觉载荷", "第三方方块适配器的渲染载荷偏大，建议检查适配器覆盖范围"),
        HEADER("头部", "资产头部异常偏大，通常是选区跨度过大导致"),
        CRC("校验", "校验字段异常偏大");

        private final String label;
        private final String hint;

        Part(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return label;
        }

        public String hint() {
            return hint;
        }
    }

    public record Warning(String code, String hint) {}

    private final int sectionCount;
    private final long nonAirBlocks;
    private final int globalPaletteEntries;
    private final int sparseSections;
    private final int packedSections;
    private final long headerBytes;
    private final long paletteBytes;
    private final long indexBytes;
    private final long lightBytes;
    private final long payloadBytes;
    private final long crcBytes;
    private final long compressedBytes;

    private SceneAssetSizeReport(int sectionCount, long nonAirBlocks, int globalPaletteEntries,
                                 int sparseSections, int packedSections, long headerBytes,
                                 long paletteBytes, long indexBytes, long lightBytes,
                                 long payloadBytes, long crcBytes, long compressedBytes) {
        this.sectionCount = sectionCount;
        this.nonAirBlocks = nonAirBlocks;
        this.globalPaletteEntries = globalPaletteEntries;
        this.sparseSections = sparseSections;
        this.packedSections = packedSections;
        this.headerBytes = headerBytes;
        this.paletteBytes = paletteBytes;
        this.indexBytes = indexBytes;
        this.lightBytes = lightBytes;
        this.payloadBytes = payloadBytes;
        this.crcBytes = crcBytes;
        this.compressedBytes = compressedBytes;
    }

    /**
     * 按 v3 布局逐项统计一份资产。
     *
     * @param nonAirBlocks 非空气方块总数。捕获期已经在累加（{@code task.totalNonAirBlocks}），
     *                     直接传进来，避免发布路径再扫一遍 4096×N。
     */
    public static SceneAssetSizeReport analyze(SceneAssetCodec.AssetData data, long nonAirBlocks,
                                               long compressedBytes) {
        long header = 4L + 4L + 4L                                    // magic + version + dataVersion
                + utfLength(data.dimensionId)
                + 6L * 4L + 6L * 4L                                  // sourceBounds + haloBounds
                + utfLength(data.fingerprint)
                + 4L;                                                // sectionCount

        long palette = 0L;
        long index = 0L;
        long light = 0L;
        int sparse = 0;
        int packed = 0;

        java.util.LinkedHashMap<String, Integer> unique = new java.util.LinkedHashMap<>();
        for (SceneAssetCodec.SectionData section : data.sections) {
            for (String entry : section.palette) {
                if (!unique.containsKey(entry)) {
                    unique.put(entry, unique.size());
                }
            }
        }
        header += 4L; // globalPaletteSize 字段
        for (String entry : unique.keySet()) {
            palette += utfLength(entry);
        }

        for (SceneAssetCodec.SectionData section : data.sections) {
            int paletteSize = section.palette.size();
            palette += 4L + 4L * paletteSize;                        // paletteSize 字段 + 全局索引引用
            index += 3L * 4L;                                        // 坐标 relX/relY/relZ

            int width = indexBitWidth(paletteSize);
            int nonZero = countNonZero(section.blockIndices);
            boolean useSparse = width > 0
                    && (512L + ((long) nonZero * width + 7L) / 8L) < (((long) 4096 * width + 7L) / 8L);
            if (useSparse) {
                sparse++;
                index += 1L + 512L + ((long) nonZero * width + 7L) / 8L;
            } else {
                packed++;
                index += 1L + (((long) 4096 * width + 7L) / 8L);
            }
            light += 2048L + 2048L;
        }

        long payload = 4L; // payloadCount
        if (data.blockPayloads != null) {
            for (var entry : data.blockPayloads) {
                long body;
                try {
                    body = entry.payload().toByteArray().length;
                } catch (Throwable ignored) {
                    body = 0L;
                }
                payload += 3L * 4L + utfLength(entry.adapterId().toString()) + 4L + 4L + body;
            }
        }

        return new SceneAssetSizeReport(data.sections.size(), nonAirBlocks, unique.size(),
                sparse, packed, header, palette, index, light, payload, 8L, compressedBytes);
    }

    private static int indexBitWidth(int paletteSize) {
        if (paletteSize <= 1) return 0;
        if (paletteSize <= 2) return 1;
        if (paletteSize <= 4) return 2;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 256) return 8;
        return 12;
    }

    private static int countNonZero(short[] values) {
        int total = 0;
        for (short value : values) {
            if (value != 0) total++;
        }
        return total;
    }

    /** {@code DataOutputStream.writeUTF} 的长度前缀（2 字节）+ 修改版 UTF-8 的字节数。 */
    private static long utfLength(String value) {
        if (value == null) return 2L;
        return 2L + value.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- 读取 ----

    public int sectionCount() { return sectionCount; }
    public long nonAirBlocks() { return nonAirBlocks; }
    public int globalPaletteEntries() { return globalPaletteEntries; }
    public int sparseSections() { return sparseSections; }
    public int packedSections() { return packedSections; }
    public long headerBytes() { return headerBytes; }
    public long paletteBytes() { return paletteBytes; }
    /** 每 section 的坐标（12 字节）+ 编码标记（1 字节）+ 索引块。 */
    public long indexBytes() { return indexBytes; }
    public long lightBytes() { return lightBytes; }
    public long payloadBytes() { return payloadBytes; }
    public long crcBytes() { return crcBytes; }
    public long compressedBytes() { return compressedBytes; }

    /** 编码后、压缩前的总字节数——六个分项之和。 */
    public long rawTotalBytes() {
        return headerBytes + paletteBytes + indexBytes + lightBytes + payloadBytes + crcBytes;
    }

    /** 压缩比（原始 ÷ 压缩）；压缩后为 0 时返回 0，不抛异常。 */
    public double compressionRatio() {
        if (compressedBytes <= 0) return 0.0;
        return (double) rawTotalBytes() / (double) compressedBytes;
    }

    /** 体积最大的分项。 */
    public Part dominantPart() {
        Part part = Part.HEADER;
        long best = headerBytes;
        if (paletteBytes > best) { best = paletteBytes; part = Part.PALETTE; }
        if (indexBytes > best) { best = indexBytes; part = Part.INDEX; }
        if (lightBytes > best) { best = lightBytes; part = Part.LIGHT; }
        if (payloadBytes > best) { best = payloadBytes; part = Part.PAYLOAD; }
        return part;
    }

    public long partBytes(Part part) {
        return switch (part) {
            case PALETTE -> paletteBytes;
            case INDEX -> indexBytes;
            case LIGHT -> lightBytes;
            case PAYLOAD -> payloadBytes;
            case CRC -> crcBytes;
            case HEADER -> headerBytes;
        };
    }

    /** 分项占比，用于日志里那句"都花在哪了"。 */
    public double partShare(Part part) {
        long total = rawTotalBytes();
        if (total <= 0) return 0.0;
        return 100.0 * partBytes(part) / total;
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    public String humanCompressed() {
        return humanBytes(compressedBytes);
    }

    /**
     * 超阈值时的告警，否则为空。
     *
     * <p>顺序即优先级：先看绝对体积（那才是玩家真正要下载的东西），再看 section 数与方块密度。
     * 告警只是建议，不阻断发布。</p>
     */
    public Optional<Warning> warning() {
        if (compressedBytes >= WARN_COMPRESSED_BYTES) {
            Part part = dominantPart();
            return Optional.of(new Warning("COMPRESSED_SIZE",
                    part.label() + "占 " + Math.round(partShare(part)) + "%：" + part.hint()));
        }
        if (sectionCount >= WARN_SECTIONS) {
            return Optional.of(new Warning("SECTION_COUNT", Part.LIGHT.hint()));
        }
        if (nonAirBlocks >= WARN_NON_AIR_BLOCKS) {
            return Optional.of(new Warning("NON_AIR_DENSITY", Part.INDEX.hint()));
        }
        return Optional.empty();
    }

    /** 一行摘要：日志与状态提示共用。 */
    public String toLogLine() {
        return "sections=" + sectionCount
                + ", 非空气=" + nonAirBlocks
                + ", 原始=" + humanBytes(rawTotalBytes())
                + " (索引=" + humanBytes(indexBytes) + " 光照=" + humanBytes(lightBytes)
                + " 调色板=" + humanBytes(paletteBytes) + " 载荷=" + humanBytes(payloadBytes) + ")"
                + ", 压缩=" + humanCompressed()
                + ", 压缩比=" + String.format(Locale.ROOT, "%.1f", compressionRatio()) + ":1"
                + ", 索引编码 稀疏=" + sparseSections + "/打包=" + packedSections
                + ", 全局调色板=" + globalPaletteEntries + " 项";
    }
}
