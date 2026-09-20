package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.scene.compat.SceneMaterialKey;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动场景第三方方块与多材质兼容性诊断报告。
 * <p>
 * 收集网格构建过程中的方块适配情况、缺失材质、异常光照、未适配方块实体及渲染路径错误。
 * </p>
 *
 * <p><b>为什么按位置聚合而不是逐条累积</b>：此前用 {@code CopyOnWriteArrayList} 保存每一条
 * 记录，每次追加都要复制整个底层数组；构建 10 万条记录累计复制的引用数量约为 n²/2。
 * 而兼容性统计（正常/警告/严重方块数、兼容率）本来就只关心「每个位置处于什么状态」，
 * 逐条保存既浪费又与统计口径不一致（FRAPI 路径会给同一个方块的每种材质各记一条 NONE）。
 * 现在按位置维护一个位掩码，计数器增量维护，统计查询从每次 O(条目数) 的流式扫描降为 O(1)。</p>
 *
 * <p><b>语义保持</b>：{@link #totalBlocks()}/{@link #normalBlocks()}/{@link #warningBlocks()}/
 * {@link #severeBlocks()}/{@link #compatibilityPercentage} 的定义与重构前完全一致，
 * 包括「某位置只要出现过 issue 就不能再算作 normal」这条跨条目规则。</p>
 */
@Environment(EnvType.CLIENT)
public final class SceneCompatibilityReport {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneCompatibilityReport.class.getSimpleName());

    /** 默认详细模式：只统计不逐条保留正常条目。 */
    private static final boolean DEFAULT_DETAILED = false;
    /** 异常条目的默认保留上限；0 表示只统计不保留。 */
    private static final int DEFAULT_ISSUE_SAMPLE_LIMIT = 512;

    public enum IssueType {
        NONE,
        MISSING_TEXTURE,
        INVALID_ATLAS,
        ABNORMAL_PACKED_LIGHT,
        MISSING_ADAPTER,
        UNSUPPORTED_RENDER_PATH,
        SKIPPED
    }

    // 位掩码：低 7 位每种 IssueType 一位；第 8/9 位区分「NONE 且带适配器」与「NONE 且无适配器」。
    private static final long ADAPTER_NONE_BIT = 1L << 8;
    private static final long PLAIN_NONE_BIT = 1L << 9;

    private static final long ISSUE_BITS = bitsOf(IssueType.MISSING_TEXTURE, IssueType.INVALID_ATLAS,
            IssueType.ABNORMAL_PACKED_LIGHT, IssueType.MISSING_ADAPTER, IssueType.UNSUPPORTED_RENDER_PATH);
    private static final long WARNING_BITS = bitsOf(IssueType.MISSING_ADAPTER, IssueType.SKIPPED,
            IssueType.ABNORMAL_PACKED_LIGHT);
    private static final long SEVERE_BITS = bitsOf(IssueType.MISSING_TEXTURE, IssueType.INVALID_ATLAS,
            IssueType.UNSUPPORTED_RENDER_PATH);
    private static final long STRICT_BLOCKING_BITS = bitsOf(IssueType.MISSING_TEXTURE, IssueType.INVALID_ATLAS,
            IssueType.MISSING_ADAPTER, IssueType.UNSUPPORTED_RENDER_PATH);
    private static final long SKIP_WARN_BLOCKING_BITS = bitsOf(IssueType.MISSING_TEXTURE,
            IssueType.INVALID_ATLAS);

    private static long bitsOf(IssueType... types) {
        long mask = 0L;
        for (IssueType type : types) mask |= 1L << type.ordinal();
        return mask;
    }

    public record Entry(
            BlockPos localPos,
            ResourceLocation blockId,
            String modelClassName,
            ResourceLocation adapterId,
            int adapterVersion,
            boolean hasBlockEntity,
            boolean hasVisualPayload,
            IssueType issueType,
            String description,
            int vertexCount,
            SceneMaterialKey materialKey,
            int minLight,
            int maxLight
    ) {
        public Entry {
            if (localPos == null) localPos = BlockPos.ZERO;
            if (blockId == null) blockId = ResourceLocation.withDefaultNamespace("air");
            if (issueType == null) issueType = IssueType.NONE;
            if (description == null) description = "";
        }

        public boolean isIssue() {
            return issueType != IssueType.NONE && issueType != IssueType.SKIPPED;
        }

        public boolean isBlocking() {
            return isBlocking(com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT);
        }

        public boolean isBlocking(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
            if (policy == com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN) {
                // 跳过并警告模式下，缺少适配器和不支持路径被降级为非阻断（跳过与警告），
                // 但严重缺失纹理与无效图集永远阻断，禁止将紫黑缺失材质发布给玩家
                return issueType == IssueType.MISSING_TEXTURE
                        || issueType == IssueType.INVALID_ATLAS;
            }
            return issueType == IssueType.MISSING_TEXTURE
                    || issueType == IssueType.INVALID_ATLAS
                    || issueType == IssueType.MISSING_ADAPTER
                    || issueType == IssueType.UNSUPPORTED_RENDER_PATH;
        }
    }

    private final boolean detailed;
    private final int issueSampleLimit;

    /** 保留的抽样条目；正常条目在非详细模式下每位置至多一条。 */
    private final List<Entry> retained;
    /** 每个位置出现过的状态位。 */
    private final Map<BlockPos, Long> positionMask = new HashMap<>();
    /** 出现过正常/适配 NONE 条目的位置，用于「每位置至多一条」判定。 */
    private final java.util.Set<BlockPos> noneRetained = new java.util.HashSet<>();

    private volatile boolean frozen;

    private int distinctPositions;
    private int adaptedPositions;
    private int normalPositions;
    private int warningPositions;
    private int severePositions;
    private int strictBlockingPositions;
    private int skipWarnBlockingPositions;

    private long totalIssues;
    private long droppedIssues;

    public SceneCompatibilityReport() {
        this(DEFAULT_DETAILED, DEFAULT_ISSUE_SAMPLE_LIMIT);
    }

    public SceneCompatibilityReport(boolean detailed) {
        this(detailed, DEFAULT_ISSUE_SAMPLE_LIMIT);
    }

    public SceneCompatibilityReport(boolean detailed, int issueSampleLimit) {
        this.detailed = detailed;
        this.issueSampleLimit = Math.max(0, issueSampleLimit);
        this.retained = new ArrayList<>(Math.min(this.issueSampleLimit, 256));
    }

    public void addEntry(Entry entry) {
        if (entry == null) return;
        if (frozen) {
            // 冻结后仍在写入说明有代码路径漏掉了 freeze()；记一次不抛异常，避免在渲染线程炸掉。
            LOGGER.warn("场景兼容性报告已冻结，忽略后续条目: {}", entry.blockId());
            return;
        }

        BlockPos pos = entry.localPos();
        Long previous = positionMask.get(pos);
        long before = previous == null ? 0L : previous;
        long after = before | maskOf(entry);
        positionMask.put(pos, after);

        if (previous == null) distinctPositions++;
        adaptedPositions += adjust(isAdapted(before), isAdapted(after));
        normalPositions += adjust(isNormal(before), isNormal(after));
        warningPositions += adjust(isWarning(before), isWarning(after));
        severePositions += adjust(isSevere(before), isSevere(after));
        strictBlockingPositions += adjust(isBlocking(before, STRICT_BLOCKING_BITS),
                isBlocking(after, STRICT_BLOCKING_BITS));
        skipWarnBlockingPositions += adjust(isBlocking(before, SKIP_WARN_BLOCKING_BITS),
                isBlocking(after, SKIP_WARN_BLOCKING_BITS));

        if (entry.isIssue()) {
            totalIssues++;
            if (retained.size() < issueSampleLimit) {
                retained.add(entry);
            } else {
                droppedIssues++;
            }
            return;
        }

        if (!detailed) return;
        // 正常/跳过条目只在详细模式下逐条保留，且每个位置至多一条：
        // FRAPI 路径会按材质给同一个方块各记一条 NONE，逐条保留会重新制造刷屏。
        if (entry.issueType() == IssueType.SKIPPED) {
            if (retained.size() < issueSampleLimit) retained.add(entry);
            else droppedIssues++;
            return;
        }
        if (noneRetained.add(pos)) {
            if (retained.size() < issueSampleLimit) retained.add(entry);
            else droppedIssues++;
        }
    }

    /**
     * 结束写入并发布不可变读视图。构建完成后必须调用；幂等。
     */
    public void freeze() {
        if (frozen) return;
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public boolean isDetailed() {
        return detailed;
    }

    private static long maskOf(Entry entry) {
        long mask = 1L << entry.issueType().ordinal();
        if (entry.issueType() == IssueType.NONE) {
            mask |= entry.adapterId() != null ? ADAPTER_NONE_BIT : PLAIN_NONE_BIT;
        }
        return mask;
    }

    private static int adjust(boolean wasTrue, boolean isTrue) {
        return (isTrue ? 1 : 0) - (wasTrue ? 1 : 0);
    }

    private static boolean isAdapted(long mask) {
        return (mask & ADAPTER_NONE_BIT) != 0L;
    }

    private static boolean isNormal(long mask) {
        return (mask & PLAIN_NONE_BIT) != 0L && (mask & ISSUE_BITS) == 0L;
    }

    private static boolean isWarning(long mask) {
        return (mask & WARNING_BITS) != 0L;
    }

    private static boolean isSevere(long mask) {
        return (mask & SEVERE_BITS) != 0L;
    }

    private static boolean isBlocking(long mask, long blockingBits) {
        return (mask & blockingBits) != 0L;
    }

    /**
     * 保留的诊断抽样条目。
     *
     * <p>契约与重构前不同：这里返回的是<b>抽样</b>而不是全部条目。精确计数请使用
     * {@link #totalIssueCount()} 与各个方块数访问器。</p>
     */
    public List<Entry> getEntries() {
        return List.copyOf(retained);
    }

    public List<Entry> getIssues() {
        return retained.stream().filter(Entry::isIssue).toList();
    }

    public List<Entry> getBlockingIssues() {
        return getBlockingIssues(com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT);
    }

    public List<Entry> getBlockingIssues(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        com.habitrain.core.api.scene.model.ScenePublishPolicy p = policy != null ? policy : com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT;
        return retained.stream().filter(e -> e.isBlocking(p)).toList();
    }

    public List<Entry> getIssuesByType(IssueType type) {
        return retained.stream().filter(e -> e.issueType() == type).toList();
    }

    public boolean isCompatible() {
        return isCompatible(com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT);
    }

    public boolean isCompatible(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        return blockingPositions(policy) == 0;
    }

    public boolean hasBlockingIssues() {
        return hasBlockingIssues(com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT);
    }

    public boolean hasBlockingIssues(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        return blockingPositions(policy) > 0;
    }

    private int blockingPositions(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        boolean skipAndWarn = policy == com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN;
        return skipAndWarn ? skipWarnBlockingPositions : strictBlockingPositions;
    }

    public int totalBlocks() {
        return distinctPositions;
    }

    public int adaptedBlocks() {
        return adaptedPositions;
    }

    public int normalBlocks() {
        return normalPositions;
    }

    public int warningBlocks() {
        return warningPositions;
    }

    public int severeBlocks() {
        return severePositions;
    }

    /** 异常条目总数（不受抽样上限影响）。 */
    public long totalIssueCount() {
        return totalIssues;
    }

    /** 因抽样上限未能逐条保留的条目数。 */
    public long droppedIssueCount() {
        return droppedIssues;
    }

    public double compatibilityPercentage(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        if (distinctPositions == 0) return 100.0;
        int blocking = blockingPositions(policy);
        return Math.max(0.0, Math.min(100.0,
                (1.0 - (double) blocking / distinctPositions) * 100.0));
    }

    public String toSummaryString() {
        return toSummaryString(com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT);
    }

    public String toSummaryString(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        long total = totalBlocks();
        long adapted = adaptedBlocks();
        long issues = totalIssueCount();
        long blocking = getBlockingIssues(policy).size();
        return String.format(java.util.Locale.ROOT,
                "SceneCompatibilityReport (policy=%s): total=%d, adapted=%d, issues=%d, blocking=%d, compatible=%b, rate=%.1f%%",
                policy, total, adapted, issues, blocking, isCompatible(policy), compatibilityPercentage(policy));
    }
}
