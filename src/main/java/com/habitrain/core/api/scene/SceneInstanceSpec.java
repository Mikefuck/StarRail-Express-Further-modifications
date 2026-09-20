package com.habitrain.core.api.scene;

import com.habitrain.core.api.scene.model.SceneProfile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 API 移动场景实例的完整描述（不可变值对象）。
 *
 * <p>这是"另一个 Mod 想在世界里放一个会动的场景"时要填的全部内容：
 * 身份（{@link #id()}）、资产（{@link #assetKey()}）、运动参数（{@link #profile()}）、
 * 时间轴（{@link #startGameTime()} / {@link #headStartSeconds()} / {@link #timeScale()} /
 * {@link #paused()}）、生命周期（{@link #durationTicks()}）、锚点（{@link #anchor()}）、
 * 可见性（{@link #visibleToAll()} / {@link #visibleTo()}）、排序（{@link #priority()}）
 * 与分组标签（{@link #tags()}）。</p>
 *
 * <p><b>实例数量没有上限。</b>API 注册的实例存放在运行时的并发注册表里，同步协议按"单实例
 * 一个包"下发，因此不会撞上配置页的"每地图 5 个背景"上限，也不需要预分配数组。</p>
 *
 * <h2>示例</h2>
 * <pre>{@code
 * SceneInstanceSpec spec = SceneInstanceSpec.builder("mymod:window_left")
 *         .assetKey("mymod_train")
 *         .profile(SceneProfileBuilder.fromMapKey("mymod_train").build())
 *         .headStartSeconds(3.0)      // 开局就已经走了 3 秒
 *         .timeScale(1.0)
 *         .durationTicks(20 * 60)     // 一分钟后自动消失
 *         .priority(10)
 *         .tag("train")
 *         .anchor(SceneInstanceAnchor.player(mikeUuid, 0, 0, 24))
 *         .build();
 * }</pre>
 */
public final class SceneInstanceSpec {
    /** 自动起跑时间的哨兵值：使用服务端当前 gameTime。 */
    public static final long AUTO_START_GAME_TIME = -1L;
    /** 时间轴缩放允许的绝对值上限。 */
    public static final double MAX_TIME_SCALE = 16.0;
    /** 单个实例 ID / 资产键的最大长度。 */
    public static final int MAX_ID_LENGTH = 128;

    private final String id;
    private final String ownerId;
    private final String dimensionKey;
    private final String assetKey;
    private final SceneProfile profile;
    private final long startGameTime;
    private final double headStartSeconds;
    private final long durationTicks;
    private final double timeScale;
    private final boolean paused;
    private final int priority;
    private final Set<String> tags;
    private final SceneInstanceAnchor anchor;
    private final boolean visibleToAll;
    private final Set<UUID> visibleTo;

    private SceneInstanceSpec(Builder builder) {
        this.id = builder.id;
        this.ownerId = builder.ownerId;
        this.dimensionKey = builder.dimensionKey;
        this.assetKey = builder.assetKey;
        this.profile = builder.profile != null ? builder.profile.copy() : SceneProfileBuilder.create().build();
        this.startGameTime = builder.startGameTime;
        this.headStartSeconds = builder.headStartSeconds;
        this.durationTicks = builder.durationTicks;
        this.timeScale = builder.timeScale;
        this.paused = builder.paused;
        this.priority = builder.priority;
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.tags));
        this.anchor = builder.anchor != null ? builder.anchor : SceneInstanceAnchor.WORLD;
        this.visibleToAll = builder.visibleToAll;
        this.visibleTo = Collections.unmodifiableSet(new LinkedHashSet<>(builder.visibleTo));
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /** 以给定实例 ID 开始构建。ID 建议使用 {@code 命名空间:路径} 形式，全局唯一。 */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** 由既有描述复制一份构建器（用于局部修改后重新提交）。 */
    public Builder toBuilder() {
        Builder builder = new Builder(id);
        builder.ownerId = ownerId;
        builder.dimensionKey = dimensionKey;
        builder.assetKey = assetKey;
        builder.profile = profile;
        builder.startGameTime = startGameTime;
        builder.headStartSeconds = 0.0;
        builder.durationTicks = durationTicks;
        builder.timeScale = timeScale;
        builder.paused = paused;
        builder.priority = priority;
        builder.tags = new LinkedHashSet<>(tags);
        builder.anchor = anchor;
        builder.visibleToAll = visibleToAll;
        builder.visibleTo = new LinkedHashSet<>(visibleTo);
        return builder;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    /** 实例唯一 ID（全服唯一；重复 ID 的 {@code spawn} 会被拒绝）。 */
    public String id() { return id; }

    /** 归属标识：用于 {@code despawnAll(owner)} 批量回收，默认取 ID 的命名空间。 */
    public String ownerId() { return ownerId; }

    /** 目标维度键（如 {@code minecraft:overworld}）；{@code null} 表示"spawn 时传入的维度"。 */
    public String dimensionKey() { return dimensionKey; }

    /** 场景资产键（{@code SceneAssetStore} 里的键，例如地图键或 {@code 地图键::habiscene::背景ID}）。 */
    public String assetKey() { return assetKey; }

    /** 运动参数。 */
    public SceneProfile profile() { return profile; }

    /** 运动时间轴起点（服务端 gameTime）；{@link #AUTO_START_GAME_TIME} 表示"使用当前时间"。 */
    public long startGameTime() { return startGameTime; }

    /** 提前起跑秒数：{@code > 0} 表示注册时就"已经运动了这么久"。 */
    public double headStartSeconds() { return headStartSeconds; }

    /** 存活时长（tick）；{@code <= 0} 表示永久存活直到被显式回收。 */
    public long durationTicks() { return durationTicks; }

    /** 时间缩放：{@code 1.0} 正常，{@code 2.0} 两倍速，{@code 0} 冻结（等价暂停）。
     *  （底层相位数学以"时间前进"为前提，因此不支持负值；需要倒放请把 {@code direction}
     *   取反，环绕模式把 {@code clockwise} 取反。） */
    public double timeScale() { return timeScale; }

    /** 是否暂停（暂停时不推进运动相位，但几何仍然渲染）。 */
    public boolean paused() { return paused; }

    /** 排序权重：数值越大越"靠后"渲染（半透明层会覆盖在更小的实例之上）。 */
    public int priority() { return priority; }

    /** 分组标签（不可变集合）。 */
    public Set<String> tags() { return tags; }

    /** 空间锚点。 */
    public SceneInstanceAnchor anchor() { return anchor; }

    /** 是否对所有玩家可见。 */
    public boolean visibleToAll() { return visibleToAll; }

    /** 白名单玩家（仅当 {@link #visibleToAll()} 为 {@code false} 时生效）。 */
    public Set<UUID> visibleTo() { return visibleTo; }

    /** 是否会在一段时间后自动回收。 */
    public boolean hasLifetime() { return durationTicks > 0L; }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 结构是否合法（ID/资产键非空且长度合规、时间缩放有限）。 */
    public boolean isValid() {
        return validationError().isEmpty();
    }

    /** 返回第一条校验错误；合法时返回 {@link Optional#empty()}。 */
    public Optional<String> validationError() {
        if (id == null || id.isBlank()) return Optional.of("实例 ID 不能为空");
        if (id.length() > MAX_ID_LENGTH) return Optional.of("实例 ID 过长（上限 " + MAX_ID_LENGTH + " 字符）");
        if (!isValidId(id)) return Optional.of("实例 ID 含非法字符（只允许 a-z0-9_.-: 与 /）: " + id);
        if (ownerId != null && ownerId.length() > MAX_ID_LENGTH) {
            return Optional.of("ownerId 过长（上限 " + MAX_ID_LENGTH + " 字符）");
        }
        if (assetKey == null || assetKey.isBlank()) return Optional.of("assetKey 不能为空");
        if (assetKey.length() > MAX_ID_LENGTH) {
            return Optional.of("assetKey 过长（上限 " + MAX_ID_LENGTH + " 字符）");
        }
        if (!Double.isFinite(timeScale)) return Optional.of("timeScale 必须是有限数值");
        if (timeScale < 0.0 || timeScale > MAX_TIME_SCALE) {
            return Optional.of("timeScale 超出允许范围 [0, " + MAX_TIME_SCALE + "]");
        }
        if (!Double.isFinite(headStartSeconds)) return Optional.of("headStartSeconds 必须是有限数值");
        if (!visibleToAll && visibleTo.isEmpty()) {
            return Optional.of("visibleToAll=false 时 visibleTo 不能为空（否则谁都看不到）");
        }
        if (tags.size() > 32) return Optional.of("标签数量上限为 32");
        return Optional.empty();
    }

    /** 供实现内部使用：ID 字符白名单。 */
    public static boolean isValidId(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == ':' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneInstanceSpec that)) return false;
        return startGameTime == that.startGameTime
                && durationTicks == that.durationTicks
                && paused == that.paused
                && priority == that.priority
                && visibleToAll == that.visibleToAll
                && Double.compare(headStartSeconds, that.headStartSeconds) == 0
                && Double.compare(timeScale, that.timeScale) == 0
                && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(dimensionKey, that.dimensionKey)
                && Objects.equals(assetKey, that.assetKey)
                && Objects.equals(profile, that.profile)
                && Objects.equals(tags, that.tags)
                && Objects.equals(anchor, that.anchor)
                && Objects.equals(visibleTo, that.visibleTo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, dimensionKey, assetKey, profile, startGameTime, headStartSeconds,
                durationTicks, timeScale, paused, priority, tags, anchor, visibleToAll, visibleTo);
    }

    @Override
    public String toString() {
        return "SceneInstanceSpec[id=" + id + ", asset=" + assetKey + ", dim=" + dimensionKey
                + ", timeScale=" + timeScale + ", paused=" + paused + ", lifetime=" + durationTicks
                + ", anchor=" + anchor + "]";
    }

    // ------------------------------------------------------------------
    // 构建器
    // ------------------------------------------------------------------

    /** {@link SceneInstanceSpec} 流式构建器。 */
    public static final class Builder {
        private final String id;
        private String ownerId;
        private String dimensionKey;
        private String assetKey = "";
        private SceneProfile profile;
        private long startGameTime = AUTO_START_GAME_TIME;
        private double headStartSeconds = 0.0;
        private long durationTicks = 0L;
        private double timeScale = 1.0;
        private boolean paused = false;
        private int priority = 0;
        private Set<String> tags = new LinkedHashSet<>();
        private SceneInstanceAnchor anchor = SceneInstanceAnchor.WORLD;
        private boolean visibleToAll = true;
        private Set<UUID> visibleTo = new LinkedHashSet<>();

        private Builder(String id) {
            this.id = id != null ? id.trim() : "";
            this.ownerId = namespaceOf(this.id);
        }

        /** 归属标识（默认取 ID 的命名空间前缀）。 */
        public Builder owner(String ownerId) {
            this.ownerId = ownerId != null && !ownerId.isBlank() ? ownerId.trim() : namespaceOf(id);
            return this;
        }

        /** 目标维度键（默认由 {@code spawn(level, spec)} 的 level 决定）。 */
        public Builder dimension(String dimensionKey) {
            this.dimensionKey = dimensionKey;
            return this;
        }

        /** 场景资产键（必填）。 */
        public Builder assetKey(String assetKey) {
            this.assetKey = assetKey != null ? assetKey.trim() : "";
            return this;
        }

        /** 运动参数（必填；可用 {@link SceneProfileBuilder} 构造）。 */
        public Builder profile(SceneProfile profile) {
            this.profile = profile;
            return this;
        }

        /**
         * 在<b>当前运动参数</b>基础上就地修改（保留其他字段）。
         *
         * <pre>{@code
         * SceneInstanceSpec.builder("mymod:scene")
         *         .profileEditor(p -> p.speed(32.0).render(r -> r.maxDistance(320)))
         *         .build();
         * }</pre>
         */
        public Builder profileEditor(java.util.function.Consumer<SceneProfileBuilder> editor) {
            if (editor != null) {
                SceneProfile base = this.profile != null ? this.profile : SceneProfile.createDefault();
                SceneProfileBuilder builder = SceneProfileBuilder.from(base);
                editor.accept(builder);
                this.profile = builder.build();
            }
            return this;
        }

        /** 直接锁定时间轴起点（服务端 gameTime）。 */
        public Builder startGameTime(long startGameTime) {
            this.startGameTime = startGameTime;
            return this;
        }

        /**
         * 提前起跑：注册时把时间轴起点往前挪 N 秒，使场景"一出现就已经走了一段"。
         * 与 {@link SceneProfileBuilder#phaseOffset(double)} 的区别是它会改变时间轴本身
         * （因此循环音效/环绕角度等一切随时间演化的量都会同步偏移）。
         */
        public Builder headStartSeconds(double seconds) {
            this.headStartSeconds = seconds;
            return this;
        }

        /** 存活时长（tick）；{@code <= 0} 表示永久。每次更新实例都会重新开始倒计时。 */
        public Builder durationTicks(long durationTicks) {
            this.durationTicks = durationTicks;
            return this;
        }

        /** 存活时长（秒）；{@code <= 0} 表示永久。 */
        public Builder durationSeconds(double seconds) {
            this.durationTicks = seconds > 0 ? (long) Math.round(seconds * 20.0) : 0L;
            return this;
        }

        /** 时间缩放（{@code 1.0} 正常，{@code 0} 冻结；不支持负值）。 */
        public Builder timeScale(double timeScale) {
            this.timeScale = timeScale;
            return this;
        }

        /** 是否暂停。 */
        public Builder paused(boolean paused) {
            this.paused = paused;
            return this;
        }

        /** 排序权重。 */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** 追加一个分组标签。 */
        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank()) this.tags.add(tag.trim());
            return this;
        }

        /** 覆盖全部标签。 */
        public Builder tags(Set<String> tags) {
            this.tags = tags != null ? new LinkedHashSet<>(tags) : new LinkedHashSet<>();
            return this;
        }

        /** 空间锚点（世界 / 玩家 / 实体）。 */
        public Builder anchor(SceneInstanceAnchor anchor) {
            this.anchor = anchor != null ? anchor : SceneInstanceAnchor.WORLD;
            return this;
        }

        /** 所有玩家可见（默认）。 */
        public Builder visibleToAll() {
            this.visibleToAll = true;
            this.visibleTo = new LinkedHashSet<>();
            return this;
        }

        /** 仅指定玩家可见（同时把 {@link #visibleToAll()} 置为 false）。 */
        public Builder visibleTo(UUID... players) {
            this.visibleToAll = false;
            this.visibleTo = new LinkedHashSet<>();
            if (players != null) {
                for (UUID player : players) {
                    if (player != null) this.visibleTo.add(player);
                }
            }
            return this;
        }

        /** 仅指定玩家可见。 */
        public Builder visibleTo(Set<UUID> players) {
            this.visibleToAll = false;
            this.visibleTo = players != null ? new LinkedHashSet<>(players) : new LinkedHashSet<>();
            return this;
        }

        /** 产出不可变描述。 */
        public SceneInstanceSpec build() {
            return new SceneInstanceSpec(this);
        }

        private static String namespaceOf(String id) {
            if (id == null || id.isBlank()) return "unknown";
            int split = id.indexOf(':');
            if (split > 0) return id.substring(0, split);
            int slash = id.indexOf('/');
            if (slash > 0) return id.substring(0, slash);
            return id;
        }
    }
}
