package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 移动场景第三方方块与多材质兼容性诊断报告。
 * <p>
 * 收集网格构建过程中的方块适配情况、缺失材质、异常光照、未适配方块实体及渲染路径错误。
 */
@Environment(EnvType.CLIENT)
public final class SceneCompatibilityReport {

    public enum IssueType {
        NONE,
        MISSING_TEXTURE,
        INVALID_ATLAS,
        ABNORMAL_PACKED_LIGHT,
        MISSING_ADAPTER,
        UNSUPPORTED_RENDER_PATH,
        SKIPPED
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
            return isBlocking(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
        }

        public boolean isBlocking(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
            if (policy == com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN) {
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

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public SceneCompatibilityReport() {}

    public void addEntry(Entry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Entry> getIssues() {
        return entries.stream().filter(Entry::isIssue).toList();
    }

    public List<Entry> getBlockingIssues() {
        return getBlockingIssues(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
    }

    public List<Entry> getBlockingIssues(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        com.habitrain.core.scene.model.ScenePublishPolicy p = policy != null ? policy : com.habitrain.core.scene.model.ScenePublishPolicy.STRICT;
        return entries.stream().filter(e -> e.isBlocking(p)).toList();
    }

    public List<Entry> getIssuesByType(IssueType type) {
        return entries.stream().filter(e -> e.issueType() == type).toList();
    }

    public boolean isCompatible() {
        return isCompatible(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
    }

    public boolean isCompatible(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        return getBlockingIssues(policy).isEmpty();
    }

    public boolean hasBlockingIssues() {
        return hasBlockingIssues(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
    }

    public boolean hasBlockingIssues(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        com.habitrain.core.scene.model.ScenePublishPolicy p = policy != null ? policy : com.habitrain.core.scene.model.ScenePublishPolicy.STRICT;
        return entries.stream().anyMatch(e -> e.isBlocking(p));
    }

    public int totalBlocks() {
        return (int) entries.stream().map(Entry::localPos).distinct().count();
    }

    public int adaptedBlocks() {
        return countDistinctBlocks(e -> e.adapterId() != null && e.issueType() == IssueType.NONE);
    }

    public int normalBlocks() {
        java.util.Set<BlockPos> issuePositions = entries.stream()
                .filter(Entry::isIssue)
                .map(Entry::localPos)
                .collect(java.util.stream.Collectors.toSet());
        return countDistinctBlocks(e -> e.issueType() == IssueType.NONE
                && e.adapterId() == null && !issuePositions.contains(e.localPos()));
    }

    public int warningBlocks() {
        return countDistinctBlocks(e -> e.issueType() == IssueType.MISSING_ADAPTER
                || e.issueType() == IssueType.SKIPPED
                || e.issueType() == IssueType.ABNORMAL_PACKED_LIGHT);
    }

    public int severeBlocks() {
        return countDistinctBlocks(e -> e.issueType() == IssueType.MISSING_TEXTURE
                || e.issueType() == IssueType.INVALID_ATLAS
                || e.issueType() == IssueType.UNSUPPORTED_RENDER_PATH);
    }

    public double compatibilityPercentage(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        if (entries.isEmpty()) return 100.0;
        com.habitrain.core.scene.model.ScenePublishPolicy p = policy != null
                ? policy : com.habitrain.core.scene.model.ScenePublishPolicy.STRICT;
        int total = totalBlocks();
        int blocking = countDistinctBlocks(e -> e.isBlocking(p));
        return total == 0 ? 100.0
                : Math.max(0.0, Math.min(100.0, (1.0 - (double) blocking / total) * 100.0));
    }

    private int countDistinctBlocks(java.util.function.Predicate<Entry> predicate) {
        return (int) entries.stream().filter(predicate).map(Entry::localPos).distinct().count();
    }

    public String toSummaryString() {
        return toSummaryString(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
    }

    public String toSummaryString(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        long total = totalBlocks();
        long adapted = adaptedBlocks();
        long issues = getIssues().size();
        long blocking = getBlockingIssues(policy).size();
        return String.format(java.util.Locale.ROOT,
                "SceneCompatibilityReport (policy=%s): total=%d, adapted=%d, issues=%d, blocking=%d, compatible=%b, rate=%.1f%%",
                policy, total, adapted, issues, blocking, isCompatible(policy), compatibilityPercentage(policy));
    }
}
