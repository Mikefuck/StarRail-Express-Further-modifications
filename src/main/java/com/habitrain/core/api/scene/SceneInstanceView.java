package com.habitrain.core.api.scene;

import com.habitrain.core.api.scene.model.SceneProfile;

import java.util.Set;
import java.util.UUID;

/**
 * API 移动场景实例的只读视图。
 *
 * <p>它是外部 Mod 能看到的全部运行时信息：身份、资产、运动参数、时间轴、生命周期、锚点、
 * 可见性、排序与标签。所有查询接口（{@code SceneApi.instances(...)}、
 * {@code SceneClientApi.instances()}、事件回调）都返回这一类型，调用方无需接触内部实现类。</p>
 */
public interface SceneInstanceView {

    /** 实例唯一 ID。 */
    String id();

    /** 归属标识（默认是 ID 的命名空间）。 */
    String ownerId();

    /** 目标维度键。 */
    String dimensionKey();

    /** 场景资产键。 */
    String assetKey();

    /** 当前解析到的资产内容哈希；为空串表示资产尚未就绪。 */
    String assetHash();

    /** 是否已经解析到可用资产。 */
    boolean hasAsset();

    /** 运动参数。 */
    SceneProfile profile();

    /** 空间锚点。 */
    SceneInstanceAnchor anchor();

    /** 时间轴起点（服务端 gameTime）。 */
    long startGameTime();

    /** 时间缩放。 */
    double timeScale();

    /** 是否暂停。 */
    boolean paused();

    /** 存活时长（tick），{@code <= 0} 表示永久。 */
    long durationTicks();

    /** 绝对回收时刻（gameTime），{@code < 0} 表示永不回收。 */
    long expireAtGameTime();

    /** 剩余存活 tick（{@code -1} = 永久）。 */
    long remainingTicks(long gameTime);

    /** 是否已过存活期。 */
    boolean isExpired(long gameTime);

    /** 排序权重。 */
    int priority();

    /** 标签集合（不可变）。 */
    Set<String> tags();

    /** 是否带有某标签。 */
    boolean hasTag(String tag);

    /** 是否所有玩家可见。 */
    boolean visibleToAll();

    /** 白名单玩家（不可变）。 */
    Set<UUID> visibleTo();

    /** 是否允许发给指定玩家（不含维度过滤）。 */
    boolean isVisibleTo(UUID playerId);

    /** 是否属于指定维度。 */
    boolean belongsTo(String dimensionKey);

    /** 更新代数（每次更新自增）。 */
    int revision();

    /** 注册时刻（真实毫秒时间戳）。 */
    long createdAtMillis();

    /** 由给定 gameTime 计算的场景相位时间（秒）。 */
    double elapsedSeconds(long gameTime, float partialTick);
}
