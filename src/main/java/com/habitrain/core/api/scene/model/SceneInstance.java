package com.habitrain.core.api.scene.model;

import com.habitrain.core.api.scene.SceneInstanceAnchor;
import com.habitrain.core.api.scene.SceneInstanceSpec;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * API 移动场景实例的服务端权威运行时快照（下发到客户端的也就是这一份字段集合）。
 *
 * <p>它把 {@link SceneInstanceSpec}（调用方意图）与"解析结果"绑定在一起：</p>
 * <ul>
 *   <li>{@code assetHash}：注册时从资产索引解析出的内容哈希，客户端据此取网格；资产缺失时为空串。</li>
 *   <li>{@code expireAtGameTime}：注册时按 {@code durationTicks} 折算出的绝对回收时刻（&lt;0 = 永不）。</li>
 *   <li>{@code pausedAtGameTime}：暂停时刻，用来冻结运动相位；恢复时由服务端把这枚时间差补回起点。</li>
 *   <li>{@code revision}：每次更新自增，用于诊断与客户端覆盖判定。</li>
 * </ul>
 *
 * <p>本类是不可变值对象，所有修改都通过 {@link #withSpec(SceneInstanceSpec, String, long)} 产生新实例，
 * 因此可以在服务端多线程环境里安全发布。</p>
 */
public final class SceneInstance implements com.habitrain.core.api.scene.SceneInstanceView {
    private final SceneInstanceSpec spec;
    private final String assetHash;
    private final int revision;
    private final long expireAtGameTime;
    private final long pausedAtGameTime;
    private final long createdAtMillis;

    public SceneInstance(SceneInstanceSpec spec, String assetHash, int revision,
                         long expireAtGameTime, long pausedAtGameTime, long createdAtMillis) {
        this.spec = Objects.requireNonNull(spec, "spec cannot be null");
        this.assetHash = assetHash != null ? assetHash : "";
        this.revision = revision;
        this.expireAtGameTime = expireAtGameTime;
        this.pausedAtGameTime = pausedAtGameTime;
        this.createdAtMillis = createdAtMillis > 0 ? createdAtMillis : System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // 身份
    // ------------------------------------------------------------------

    public String id() { return spec.id(); }
    public String ownerId() { return spec.ownerId(); }
    public String dimensionKey() { return spec.dimensionKey() != null ? spec.dimensionKey() : ""; }
    public String assetKey() { return spec.assetKey(); }
    public String assetHash() { return assetHash; }
    /** 是否已经解析到可用资产（客户端能取到网格）。 */
    public boolean hasAsset() { return assetHash != null && !assetHash.isBlank(); }

    // ------------------------------------------------------------------
    // 运动参数
    // ------------------------------------------------------------------

    /**
     * 运动参数。
     *
     * <p>审核 S-02：返回<b>防御性副本</b>。旧实现直接把内部 {@link SceneProfile}
     * 交出去，调用方 {@code view.profile().setXxx(...)} 既不发包也不自增 revision，
     * 却会在下一次任意同步时被顺带序列化上线，行为不可预测。</p>
     */
    @Override
    public SceneProfile profile() { return spec.profile().copy(); }

    /** 内部热路径用：直接引用（只读，禁止改写）。 */
    public SceneProfile profileRaw() { return spec.profile(); }
    public SceneInstanceAnchor anchor() { return spec.anchor(); }
    public long startGameTime() { return spec.startGameTime(); }
    public double timeScale() { return spec.timeScale(); }
    public boolean paused() { return spec.paused(); }
    public long pausedAtGameTime() { return pausedAtGameTime; }
    public long durationTicks() { return spec.durationTicks(); }
    public long expireAtGameTime() { return expireAtGameTime; }
    public int priority() { return spec.priority(); }
    public Set<String> tags() { return spec.tags(); }
    public boolean visibleToAll() { return spec.visibleToAll(); }
    public Set<UUID> visibleTo() { return spec.visibleTo(); }
    public int revision() { return revision; }
    public long createdAtMillis() { return createdAtMillis; }

    /** 原始描述（已归一化：起跑时间已解析、提前起跑已折算）。 */
    public SceneInstanceSpec spec() { return spec; }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /** 是否已过存活期（{@code expireAtGameTime < 0} 表示永不）。 */
    public boolean isExpired(long gameTime) {
        return expireAtGameTime >= 0L && gameTime >= expireAtGameTime;
    }

    /** 剩余存活 tick；{@code -1} 表示永久。 */
    public long remainingTicks(long gameTime) {
        if (expireAtGameTime < 0L) return -1L;
        return Math.max(0L, expireAtGameTime - gameTime);
    }

    /** 是否属于指定维度键。 */
    public boolean belongsTo(String dimensionKey) {
        return dimensionKey != null && dimensionKey.equals(this.dimensionKey());
    }

    /** 该实例是否允许发给指定玩家（维度过滤由调用方负责）。 */
    public boolean isVisibleTo(UUID playerId) {
        if (visibleToAll()) return true;
        return playerId != null && spec.visibleTo().contains(playerId);
    }

    /** 是否带有某个标签。 */
    public boolean hasTag(String tag) {
        return tag != null && spec.tags().contains(tag);
    }

    /**
     * 计算自时间轴起点以来经过的场景秒数（客户端确定性推进，与服务端无关）。
     *
     * @param gameTime    当前（客户端）gameTime
     * @param partialTick 帧内插值 tick 分量；暂停时不参与
     */
    public double elapsedSeconds(long gameTime, float partialTick) {
        long now = paused() && pausedAtGameTime >= 0L ? pausedAtGameTime : gameTime;
        double ticks = (double) (now - startGameTime());
        if (!paused()) {
            ticks += partialTick;
        }
        if (ticks < 0.0) ticks = 0.0;
        double scale = spec.timeScale();
        if (scale <= 0.0) return 0.0;
        return ticks * scale / 20.0;
    }

    /** 归一化后的时间轴起点（应用提前起跑后的结果）。 */
    public long resolvedStartGameTime() {
        return spec.startGameTime();
    }

    // ------------------------------------------------------------------
    // 派生
    // ------------------------------------------------------------------

    /**
     * 生成一份新的运行时快照。
     *
     * @param newSpec             新的描述（身份 ID 必须一致）
     * @param newAssetHash        新解析出的资产哈希
     * @param newExpireAtGameTime 新的绝对回收时刻（&lt;0 = 永不）
     * @param newPausedAtGameTime 暂停时刻（&lt;0 = 未暂停）
     */
    public SceneInstance withSpec(SceneInstanceSpec newSpec, String newAssetHash,
                                  long newExpireAtGameTime, long newPausedAtGameTime) {
        Objects.requireNonNull(newSpec, "spec cannot be null");
        return new SceneInstance(newSpec, newAssetHash, revision + 1,
                newExpireAtGameTime, newPausedAtGameTime, createdAtMillis);
    }

    /** 生成一份"仅替换资产哈希"的新快照（资产热更新路径）。 */
    public SceneInstance withAssetHash(String newAssetHash) {
        return new SceneInstance(spec, newAssetHash, revision + 1, expireAtGameTime, pausedAtGameTime, createdAtMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneInstance that)) return false;
        return revision == that.revision
                && expireAtGameTime == that.expireAtGameTime
                && pausedAtGameTime == that.pausedAtGameTime
                && Objects.equals(spec, that.spec)
                && Objects.equals(assetHash, that.assetHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spec, assetHash, revision, expireAtGameTime, pausedAtGameTime);
    }

    @Override
    public String toString() {
        return "SceneInstance[" + id() + " asset=" + assetKey() + " hash="
                + (assetHash.length() >= 8 ? assetHash.substring(0, 8) : assetHash)
                + " rev=" + revision + " dim=" + dimensionKey() + "]";
    }
}
