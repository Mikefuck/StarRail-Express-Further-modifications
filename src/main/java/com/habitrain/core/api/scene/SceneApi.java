package com.habitrain.core.api.scene;

import com.google.gson.JsonObject;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBackgroundConfig;
import com.habitrain.core.scene.model.SceneBackgroundKey;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.scene.model.SceneMotionMode;
import com.habitrain.core.scene.model.SceneOrbitSettings;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import com.habitrain.core.scene.server.SceneAssetStore;
import com.habitrain.core.scene.server.SceneCaptureService;
import com.habitrain.core.scene.server.SceneContextResolver;
import com.habitrain.core.scene.server.SceneInstanceService;
import com.habitrain.core.scene.server.SceneRuntimeCoordinator;
import com.habitrain.core.scene.server.SceneTransferService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 移动场景系统 v2 公开 API（面向外部 Mod 的完整能力面）。
 *
 * <p>它与旧的 {@link SceneMotionApi} 是<b>同一套底层实现</b>的两层门面：{@code SceneMotionApi}
 * 只暴露"读配置 / 手动开关地图场景"这几件事，{@code SceneApi} 则把整个系统摊开——
 * 无数量上限的运行时实例、逐字段实时调参、资产发布、区域捕获、事件监听、诊断快照。</p>
 *
 * <h2>一、两种"移动场景"</h2>
 * <table border="1">
 *   <caption>通道对比</caption>
 *   <tr><th></th><th>地图级场景（配置页）</th><th>API 实例（本类）</th></tr>
 *   <tr><td>数量</td><td>每地图 1 主背景 + 最多 4 附加背景</td><td><b>无上限</b></td></tr>
 *   <tr><td>持久化</td><td>写入 {@code config/habitrain_core.json}</td><td>纯运行时，服务器停止即清空</td></tr>
 *   <tr><td>归属</td><td>地图配置</td><td>注册它的 Mod（{@link SceneInstanceSpec#ownerId()}）</td></tr>
 *   <tr><td>生命周期</td><td>跟随对局开始/结束</td><td>由调用方显式注册/回收，可设存活时长</td></tr>
 *   <tr><td>控制</td><td>配置页 / 配置器道具</td><td>本 API 逐字段实时修改</td></tr>
 * </table>
 *
 * <h2>二、最短上手路径</h2>
 * <pre>{@code
 * // 1) 准备一张资产（这里直接复用地图 mymap 已发布的资产）
 * //    也可以 publishAsset(...) 自己发布 .hscene 字节。
 *
 * // 2) 注册一个实例（数量不限，可以注册任意多个）
 * SceneSpawnResult result = SceneApi.instance().spawn(serverLevel,
 *         SceneInstanceSpec.builder("mymod:window_a")
 *                 .assetKey("mymap")
 *                 .profileEditor(p -> p.displayOrigin(0, 64, 300)
 *                         .direction(-1, 0, 0)
 *                         .speed(24.0)
 *                         .loopCustom(true, 512.0))
 *                 .build());
 *
 * // 3) 运行时实时调参（立即下发到客户端，无需重启、无需重发资产）
 * SceneApi api = SceneApi.instance();
 * api.setSpeed("mymod:window_a", 40.0);
 * api.setPaused("mymod:window_a", true);
 *
 * // 4) 回收
 * api.despawn("mymod:window_a");
 * }</pre>
 *
 * <h2>三、线程约定</h2>
 * <p>所有写操作都会立刻改动服务端注册表并<b>同步</b>发送网络包，因此必须在服务端主线程调用
 * （tick、命令、事件回调里都是主线程）。读操作可以在任意线程调用。</p>
 */
public final class SceneApi {
    private static final SceneApi INSTANCE = new SceneApi();

    public static SceneApi instance() {
        return INSTANCE;
    }

    private SceneApi() {}

    private static SceneInstanceService service() {
        return SceneInstanceService.getInstance();
    }

    private static SceneRuntimeCoordinator coordinator() {
        return SceneRuntimeCoordinator.getInstance();
    }

    private static SceneMotionSettings settings() {
        return ConfigManager.getInstance().getSceneMotionSettings();
    }

    // ==================================================================
    // 1. 全局开关与配置根
    // ==================================================================

    /** 场景系统配置根（{@code sceneMotion} 节点）。 */
    public SceneMotionSettings config() {
        return settings();
    }

    /** 移动场景总开关。关闭时：地图级场景不启动，API 实例只登记但不下发。 */
    public boolean isGlobalEnabled() {
        return settings().enabled;
    }

    /** 修改总开关并标记落盘。 */
    public void setGlobalEnabled(boolean enabled) {
        settings().enabled = enabled;
        ConfigManager.getInstance().markSceneMotionDirty();
    }

    /** 地图键常量：未配置地图的回退键。 */
    public String defaultMapKey() {
        return SceneMotionSettings.DEFAULT_MAP_KEY;
    }

    /** 地图键常量：非对局大厅。 */
    public String lobbyMapKey() {
        return SceneMotionSettings.LOBBY_MAP_KEY;
    }

    /** 是否是大厅键。 */
    public boolean isLobbyMapKey(String mapKey) {
        return SceneMotionSettings.LOBBY_MAP_KEY.equals(mapKey);
    }

    /** 配置页每张地图允许的附加背景数量上限（仅约束地图级配置通道，不约束 API 实例）。 */
    public int maxBackgroundsPerMap() {
        return SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP;
    }

    // ==================================================================
    // 2. 资产键助手
    // ==================================================================

    /** 规范化地图键（空白回退到默认键）。 */
    public String normalizeMapKey(String mapKey) {
        return SceneBackgroundKey.normalizeMapKey(mapKey);
    }

    /** 主背景资产键（等价于地图键本身）。 */
    public String primaryAssetKey(String mapKey) {
        return SceneBackgroundKey.assetKey(mapKey, SceneBackgroundKey.DEFAULT_ID);
    }

    /** 附加背景资产键。 */
    public String backgroundAssetKey(String mapKey, String backgroundId) {
        return SceneBackgroundKey.assetKey(mapKey, backgroundId);
    }

    /** 从资产键反解地图键。 */
    public String mapKeyFromAssetKey(String assetKey) {
        return SceneBackgroundKey.mapKeyFromAssetKey(assetKey);
    }

    /** 从资产键反解背景 ID（主背景返回 {@code __default__}）。 */
    public String backgroundIdFromAssetKey(String assetKey) {
        return SceneBackgroundKey.backgroundIdFromAssetKey(assetKey);
    }

    // ==================================================================
    // 3. 实例查询（无数量上限）
    // ==================================================================

    /** 当前登记的实例总数（所有维度）。 */
    public int instanceCount() {
        return service().count();
    }

    /** 某维度的实例数量。 */
    public int instanceCountIn(String dimensionKey) {
        return service().countIn(dimensionKey);
    }

    /** 全部实例（按 priority / 注册顺序排序）。 */
    public List<SceneInstanceView> instances() {
        return List.copyOf(service().all());
    }

    /** 某维度的全部实例。 */
    public List<SceneInstanceView> instancesIn(ServerLevel level) {
        return level == null ? List.of() : List.copyOf(service().byDimension(dimensionKey(level)));
    }

    /** 某维度键的全部实例。 */
    public List<SceneInstanceView> instancesIn(String dimensionKey) {
        return List.copyOf(service().byDimension(dimensionKey));
    }

    /** 某个归属方的全部实例。 */
    public List<SceneInstanceView> instancesOfOwner(String ownerId) {
        return List.copyOf(service().byOwner(ownerId));
    }

    /** 带某标签的全部实例。 */
    public List<SceneInstanceView> instancesWithTag(String tag) {
        return List.copyOf(service().byTag(tag));
    }

    /** 查询单个实例。 */
    public Optional<SceneInstanceView> instance(String instanceId) {
        return service().get(instanceId).map(view -> (SceneInstanceView) view);
    }

    /** 是否存在某实例。 */
    public boolean hasInstance(String instanceId) {
        return service().exists(instanceId);
    }

    // ==================================================================
    // 4. 实例生命周期
    // ==================================================================

    /**
     * 注册实例（维度取自 {@code spec.dimensionKey()}）。
     * ID 已存在时不会覆盖，返回 {@code ALREADY_EXISTS}。
     */
    public SceneSpawnResult spawn(SceneInstanceSpec spec) {
        return service().spawn(spec);
    }

    /** 注册实例（维度取自 {@code level}）。ID 已存在时不会覆盖。 */
    public SceneSpawnResult spawn(ServerLevel level, SceneInstanceSpec spec) {
        return service().spawn(level, spec);
    }

    /** 注册或覆盖实例（幂等；重复调用同一份 spec 只会刷新 revision 并重发）。 */
    public SceneSpawnResult upsert(SceneInstanceSpec spec) {
        return service().upsert(spec);
    }

    /** 注册或覆盖实例（维度取自 {@code level}）。 */
    public SceneSpawnResult upsert(ServerLevel level, SceneInstanceSpec spec) {
        return service().upsert(level, spec);
    }

    /** 回收单个实例。 */
    public boolean despawn(String instanceId) {
        return service().despawn(instanceId);
    }

    /** 回收某归属方的全部实例，返回回收数量。 */
    public int despawnAllOfOwner(String ownerId) {
        return service().despawnAll(ownerId);
    }

    /** 回收带某标签的全部实例，返回回收数量。 */
    public int despawnAllWithTag(String tag) {
        return service().despawnByTag(tag);
    }

    /** 按谓词批量回收，返回回收数量。 */
    public int despawnMatching(Predicate<SceneInstanceView> predicate) {
        return service().despawnMatching(predicate);
    }

    /** 回收某维度的全部实例，返回回收数量。 */
    public int despawnAllIn(ServerLevel level) {
        return level == null ? 0 : service().despawnInDimension(dimensionKey(level));
    }

    /** 回收全部 API 实例，返回回收数量。 */
    public int despawnAll() {
        return service().despawnAllInstances();
    }

    /** 立即重发单个实例（不改参数）。 */
    public boolean resync(String instanceId) {
        return service().resync(instanceId);
    }

    /** 立即重发某维度全部实例，返回重发数量。 */
    public int resyncDimension(ServerLevel level) {
        return level == null ? 0 : service().resyncDimension(dimensionKey(level));
    }

    /** 让所有在线玩家重新接收全部实例。 */
    public void resyncAll() {
        service().resyncAll();
    }

    /**
     * 就地修改实例描述（以当前描述为基底）。所有 {@code setXxx} 都是它的语法糖，
     * 需要一次改多个字段时直接用它更高效。
     */
    public boolean modify(String instanceId, Consumer<SceneInstanceSpec.Builder> mutation) {
        return service().mutate(instanceId, mutation);
    }

    // ---- 逐字段实时调参 ----

    /** 整体替换运动参数。 */
    public boolean setProfile(String instanceId, SceneProfile profile) {
        return modify(instanceId, builder -> builder.profile(profile));
    }

    /** 用 {@link SceneProfileBuilder} 就地修改运动参数（保留未提及字段）。 */
    public boolean editProfile(String instanceId, Consumer<SceneProfileBuilder> editor) {
        return modify(instanceId, builder -> builder.profileEditor(editor));
    }

    /** 开始/停止渲染该实例（profile.enabled）。 */
    public boolean setProfileEnabled(String instanceId, boolean enabled) {
        return editProfile(instanceId, profile -> profile.enabled(enabled));
    }

    /** 运动速度（格/秒）。 */
    public boolean setSpeed(String instanceId, double blocksPerSecond) {
        return editProfile(instanceId, profile -> profile.speed(blocksPerSecond));
    }

    /** 运动方向（自动归一化）。 */
    public boolean setDirection(String instanceId, double x, double y, double z) {
        return editProfile(instanceId, profile -> profile.direction(x, y, z));
    }

    /** 显示原点（静态世界坐标；配合 WORLD 锚点使用）。 */
    public boolean setDisplayOrigin(String instanceId, double x, double y, double z) {
        return editProfile(instanceId, profile -> profile.displayOrigin(x, y, z));
    }

    /** 模型自身欧拉角旋转（度）。 */
    public boolean setRotation(String instanceId, double yaw, double pitch, double roll) {
        return editProfile(instanceId, profile -> profile.rotation(yaw, pitch, roll));
    }

    /** 相位偏移（格）。 */
    public boolean setPhaseOffset(String instanceId, double blocks) {
        return editProfile(instanceId, profile -> profile.phaseOffset(blocks));
    }

    /** 循环平铺设置。 */
    public boolean setLoop(String instanceId, boolean enabled, SceneLoopDistanceMode mode, double distanceBlocks) {
        return editProfile(instanceId, profile -> profile.loop(new com.habitrain.core.scene.model.SceneLoopSettings(
                enabled,
                mode != null ? mode : SceneLoopDistanceMode.AUTO,
                distanceBlocks,
                2)));
    }

    /** 渲染距离（格）。 */
    public boolean setRenderDistance(String instanceId, double blocks) {
        return editProfile(instanceId, profile -> profile.renderDistance(blocks));
    }

    /** 是否渲染半透明层。 */
    public boolean setTranslucent(String instanceId, boolean translucent) {
        return editProfile(instanceId, profile -> profile.translucent(translucent));
    }

    /** 运动模式：直线 / 环绕。 */
    public boolean setMotionMode(String instanceId, SceneMotionMode mode) {
        return editProfile(instanceId, profile -> profile.motionMode(mode));
    }

    /** 环绕参数（会自动切换到环绕模式）。 */
    public boolean setOrbit(String instanceId, Consumer<SceneProfileBuilder.Orbit> orbit) {
        return editProfile(instanceId, profile -> profile.orbit(orbit));
    }

    /** 整体替换环绕参数（不改变运动模式）。 */
    public boolean setOrbitSettings(String instanceId, SceneOrbitSettings orbit) {
        return editProfile(instanceId, profile -> profile.orbitSettings(orbit));
    }

    /** 车外环境音。 */
    public boolean editSound(String instanceId, Consumer<SceneProfileBuilder.Sound> editor) {
        return editProfile(instanceId, profile -> profile.sound(editor));
    }

    /** 镜头微震。 */
    public boolean editShake(String instanceId, Consumer<SceneProfileBuilder.Shake> editor) {
        return editProfile(instanceId, profile -> profile.shake(editor));
    }

    /** 更换实例使用的资产键（客户端会拉取新网格并热切换）。 */
    public boolean setAssetKey(String instanceId, String assetKey) {
        return modify(instanceId, builder -> builder.assetKey(assetKey));
    }

    /** 更换空间锚点。 */
    public boolean setAnchor(String instanceId, SceneInstanceAnchor anchor) {
        return modify(instanceId, builder -> builder.anchor(anchor));
    }

    /** 开始/解除暂停（解除时暂停期间的时间会被补回时间轴，不会跳变）。 */
    public boolean setPaused(String instanceId, boolean paused) {
        return modify(instanceId, builder -> builder.paused(paused));
    }

    /** 时间缩放：{@code 1.0} 正常，{@code 2.0} 两倍速，{@code 0} 冻结。 */
    public boolean setTimeScale(String instanceId, double timeScale) {
        return modify(instanceId, builder -> builder.timeScale(timeScale));
    }

    /** 重置时间轴起点为"现在"（场景回到起始相位）。 */
    public boolean restart(String instanceId) {
        return service().restart(instanceId);
    }

    /** 显式锁定时间轴起点（服务端 gameTime）。 */
    public boolean setStartGameTime(String instanceId, long gameTime) {
        return modify(instanceId, builder -> builder.startGameTime(gameTime));
    }

    /** 提前起跑秒数（注册/更新时把时间轴往前挪，用来让场景"一出现就已经走了一段"）。 */
    public boolean setHeadStartSeconds(String instanceId, double seconds) {
        return modify(instanceId, builder -> builder.headStartSeconds(seconds));
    }

    /** 存活时长（tick，{@code <= 0} 表示永久）。每次更新都会重新开始倒计时。 */
    public boolean setDurationTicks(String instanceId, long ticks) {
        return modify(instanceId, builder -> builder.durationTicks(ticks));
    }

    /** 存活时长（秒）。 */
    public boolean setDurationSeconds(String instanceId, double seconds) {
        return modify(instanceId, builder -> builder.durationSeconds(seconds));
    }

    /** 排序权重。 */
    public boolean setPriority(String instanceId, int priority) {
        return modify(instanceId, builder -> builder.priority(priority));
    }

    /** 追加标签。 */
    public boolean addTag(String instanceId, String tag) {
        return modify(instanceId, builder -> builder.tag(tag));
    }

    /** 覆盖标签集合。 */
    public boolean setTags(String instanceId, Set<String> tags) {
        return modify(instanceId, builder -> builder.tags(tags));
    }

    /** 对所有玩家可见。 */
    public boolean setVisibleToAll(String instanceId) {
        return modify(instanceId, SceneInstanceSpec.Builder::visibleToAll);
    }

    /** 只对指定玩家可见。 */
    public boolean setVisibleTo(String instanceId, UUID... players) {
        return modify(instanceId, builder -> builder.visibleTo(players));
    }

    /** 只对指定玩家可见。 */
    public boolean setVisibleTo(String instanceId, Set<UUID> players) {
        return modify(instanceId, builder -> builder.visibleTo(players));
    }

    /** 目标维度（会把实例迁移到另一个维度并重新同步）。 */
    public boolean setDimension(String instanceId, String dimensionKey) {
        return service().moveToDimension(instanceId, dimensionKey);
    }

    // ==================================================================
    // 5. 地图级（配置页）场景
    // ==================================================================

    /** 某维度当前的场景运行状态。 */
    public SceneRuntimeState runtimeState(ServerLevel level) {
        return coordinator().getRuntimeState(level);
    }

    /** 某维度是否有场景正在运动。 */
    public boolean isSceneActive(ServerLevel level) {
        SceneRuntimeState state = coordinator().getRuntimeState(level);
        return state != null && state.isActive();
    }

    /** 手动启动某维度的地图级场景。 */
    public boolean startScene(ServerLevel level, String mapKey) {
        return coordinator().activate(level, mapKey);
    }

    /** 手动停止某维度的地图级场景。 */
    public boolean stopScene(ServerLevel level) {
        return coordinator().deactivate(level);
    }

    /** 解析某维度当前的地图上下文（地图键 / 维度键 / 是否对局中）。 */
    public SceneContextResolver.SceneContext resolveContext(ServerLevel level) {
        return coordinator() != null ? coordinator().resolveContext(level) : null;
    }

    /** 注册自定义地图上下文解析器（替换默认的 SRE 解析器；传 null 恢复默认）。 */
    public void registerContextResolver(SceneContextResolver resolver) {
        coordinator().setContextResolver(resolver);
    }

    // ==================================================================
    // 6. Profile 与附加背景配置
    // ==================================================================

    /** 读取某地图的主背景 profile（不存在时回退默认键）。 */
    public SceneProfile profile(String mapKey) {
        return settings().getProfile(mapKey);
    }

    /** 读取或创建某地图的 profile。 */
    public SceneProfile getOrCreateProfile(String mapKey) {
        return settings().getOrCreateProfile(mapKey);
    }

    /** 写入某地图的主背景 profile 并标记落盘。 */
    public void setMapProfile(String mapKey, SceneProfile profile) {
        Objects.requireNonNull(profile, "profile cannot be null");
        String key = (mapKey != null && !mapKey.isBlank()) ? mapKey : settings().defaultMapKey;
        settings().profiles.put(key, profile);
        ConfigManager.getInstance().markSceneMotionDirty();
    }

    /** 就地修改某地图的主背景 profile。 */
    public void editMapProfile(String mapKey, Consumer<SceneProfileBuilder> editor) {
        SceneProfileBuilder builder = SceneProfileBuilder.from(profile(mapKey));
        if (editor != null) editor.accept(builder);
        setMapProfile(mapKey, builder.build());
    }

    /** 全部已配置的地图键。 */
    public Set<String> profileMapKeys() {
        return Set.copyOf(settings().profiles.keySet());
    }

    /** 某地图的全部已解析背景（含主背景；主背景的 {@code fallback} 为 true）。 */
    public List<SceneMotionSettings.ResolvedBackground> backgrounds(String mapKey) {
        return settings().getResolvedBackgrounds(mapKey);
    }

    /** 读取附加背景 profile（不存在时回退主背景）。 */
    public SceneProfile backgroundProfile(String mapKey, String backgroundId) {
        return settings().getBackgroundProfile(mapKey, backgroundId);
    }

    /** 附加背景显示名。 */
    public String backgroundName(String mapKey, String backgroundId) {
        return settings().getBackgroundName(mapKey, backgroundId);
    }

    /** 新增/覆盖一个附加背景（受配置通道的每图 4 个上限约束；要无限数量请用 API 实例）。 */
    public boolean putBackground(String mapKey, String backgroundId, String name, SceneProfile profile) {
        boolean ok = settings().putBackground(mapKey, backgroundId,
                new SceneBackgroundConfig(name, profile));
        if (ok) ConfigManager.getInstance().markSceneMotionDirty();
        return ok;
    }

    /** 删除一个附加背景。 */
    public boolean removeBackground(String mapKey, String backgroundId) {
        boolean ok = settings().removeBackground(mapKey, backgroundId);
        if (ok) ConfigManager.getInstance().markSceneMotionDirty();
        return ok;
    }

    // ==================================================================
    // 7. 资产
    // ==================================================================

    /** 查询某资产键已发布的描述符。 */
    public SceneAssetDescriptor asset(String assetKey) {
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(assetKey);
        return descriptor != null ? descriptor : SceneAssetDescriptor.EMPTY;
    }

    /** 是否已有可用资产。 */
    public boolean hasAsset(String assetKey) {
        SceneAssetDescriptor descriptor = asset(assetKey);
        return descriptor != null && descriptor.isValid();
    }

    /** 全部已发布资产的只读视图。 */
    public Map<String, SceneAssetDescriptor> assets() {
        return SceneAssetStore.getInstance().getAllDescriptors();
    }

    /** 删除某资产键（配置文件不会被删除，只是从索引里摘掉）。 */
    public boolean deleteAsset(String assetKey) {
        if (!hasAsset(assetKey)) return false;
        SceneAssetStore.getInstance().deleteAsset(assetKey);
        return true;
    }

    /**
     * 发布一份已经编码好的 {@code .hscene} 字节（GZIP 压缩的 {@link SceneAssetCodec} 容器）。
     *
     * <p>流程：格式解码校验 → 体积上限校验 → 内容寻址落盘 → 索引原子替换 → 广播 Manifest 并
     * 热更新所有引用该资产的运行中实例。整个方法在调用线程同步执行（含磁盘写），
     * <b>请在服务端主线程之外的线程生成字节</b>，或接受一次磁盘写带来的卡顿。</p>
     *
     * @return 是否发布成功
     */
    public boolean publishAsset(String assetKey, byte[] compressedBytes) {
        if (assetKey == null || assetKey.isBlank() || compressedBytes == null || compressedBytes.length == 0) {
            return false;
        }
        if (compressedBytes.length > SceneTransferService.MAX_FILE_SIZE) {
            return false;
        }
        MinecraftServer server = service().server();
        if (server == null) return false;
        try {
            SceneAssetCodec.AssetData data = SceneAssetCodec.decode(compressedBytes);
            long uncompressed = SceneAssetCodec.estimateUncompressedSize(data);
            if (uncompressed <= 0L || uncompressed > SceneAssetCodec.MAX_UNCOMPRESSED_BYTES) {
                return false;
            }
            String sha256 = SceneAssetCodec.calculateSha256(compressedBytes);
            SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                    sha256, uncompressed, compressedBytes.length, data.sections.size(),
                    data.dataVersion, data.fingerprint, System.currentTimeMillis());
            if (!SceneAssetStore.getInstance().saveAsset(assetKey, compressedBytes, descriptor)) {
                return false;
            }
            coordinator().onAssetPublished(server, assetKey, descriptor);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 用未压缩的 {@link SceneAssetCodec.AssetData} 直接发布资产（内部负责编码与校验）。
     *
     * <p>适合"用代码构造场景几何"的 Mod：自己组 {@code SectionData} 列表后调用本方法即可。</p>
     */
    public boolean publishAssetData(String assetKey, SceneAssetCodec.AssetData data) {
        if (data == null) return false;
        try {
            return publishAsset(assetKey, SceneAssetCodec.encode(data));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 发起一次"世界区域捕获"（把世界里的建筑选区采集为场景资产）。
     *
     * <p><b>注意</b>：捕获走的是管理员的<b>诊断暂存</b>流程——生成完成后资产进入暂存区，
     * 需要由持有配置器的管理员在诊断页确认后才会成为正式资产。这是既有安全设计，
     * API 不绕过它；需要"立刻可用"的 Mod 请用 {@link #publishAsset(String, byte[])}。</p>
     *
     * @param requester 接收进度提示的玩家（通常是发起者）
     * @return 是否成功排队
     */
    public boolean requestCapture(ServerLevel level, String assetKey, SceneBounds bounds, ServerPlayer requester) {
        if (level == null || requester == null || bounds == null || bounds.isEmpty()) return false;
        String key = (assetKey != null && !assetKey.isBlank()) ? assetKey : settings().defaultMapKey;
        return SceneCaptureService.getInstance().requestCapture(level, key, bounds, requester);
    }

    /** 取消某发起者正在进行的捕获任务。 */
    public boolean cancelCapture(UUID requesterPlayerId, String reason) {
        return SceneCaptureService.getInstance()
                .cancelCapture(requesterPlayerId, reason != null ? reason : "api_cancel");
    }

    // ==================================================================
    // 8. 事件
    // ==================================================================

    /** 注册服务端事件监听器。 */
    public void addListener(SceneListener listener) {
        service().addListener(listener);
    }

    /** 注销事件监听器。 */
    public void removeListener(SceneListener listener) {
        service().removeListener(listener);
    }

    // ==================================================================
    // 9. 诊断
    // ==================================================================

    /** 实例注册表的完整 JSON 快照（含每个实例的 profile）。 */
    public JsonObject diagnostics() {
        return service().toJson();
    }

    /** 实例注册表的可读多行摘要（聊天栏/命令输出用，已带颜色代码）。 */
    public List<String> describeInstances() {
        return service().describe();
    }

    /** 不可变实例快照集合（内部结构用）。 */
    public Collection<? extends SceneInstanceView> snapshot() {
        return service().snapshot();
    }

    private static String dimensionKey(ServerLevel level) {
        return level.dimension().location().toString();
    }
}
