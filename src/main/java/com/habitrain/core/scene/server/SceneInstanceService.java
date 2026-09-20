package com.habitrain.core.scene.server;

import com.habitrain.core.api.spi.SceneInstanceBridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneInstanceView;
import com.habitrain.core.api.scene.SceneListener;
import com.habitrain.core.api.scene.SceneSpawnResult;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneInstance;
import com.habitrain.core.scene.network.SceneAssetManifestS2C;
import com.habitrain.core.scene.network.SceneInstancesS2C;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * API 移动场景实例注册表与服务端权威同步器。
 *
 * <h2>为什么是独立的服务</h2>
 * <p>配置页的"地图移动场景"是<b>每张地图一份</b>的持久化配置，同时存在的数量受
 * {@code MAX_BACKGROUNDS_PER_MAP} 与主背景唯一性约束。本服务提供的是另一条通道：
 * <b>外部 Mod 在运行时注册的、数量没有上限的</b>场景实例。两条通道互不影响——
 * 地图级场景由 {@link SceneRuntimeCoordinator} 负责，实例级场景由这里负责，客户端把两者
 * 分别渲染。</p>
 *
 * <h2>同步模型</h2>
 * <ul>
 *   <li>注册表：{@code ConcurrentHashMap<实例ID, SceneInstance>}，不设容量上限。</li>
 *   <li>下发：每个变更按单实例一包发送（删除按批），因此实例数量不影响单包大小。</li>
 *   <li>维度：实例属于一个维度，只发给该维度内的玩家；换维度时整维重发。</li>
 *   <li>加入：玩家 JOIN 时先清空再全量重发，避免重连残留。</li>
 *   <li>自愈：客户端在"没换会话但清空过状态"的场合（对局结束）会主动请求重同步。</li>
 *   <li>生命周期：实例是<b>纯运行时</b>状态，不写配置、不跨存档保存；服务器停止即全部清空。</li>
 * </ul>
 */
public final class SceneInstanceService implements SceneInstanceBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneInstanceService.class.getSimpleName());

    private static final SceneInstanceService INSTANCE = new SceneInstanceService();

    public static SceneInstanceService getInstance() {
        return INSTANCE;
    }

    /** 同一玩家两次重同步请求之间的最小间隔（tick）。 */
    private static final int RESYNC_COOLDOWN_TICKS = 20;
    /** 维度漂移巡检周期（tick）。 */
    private static final int DRIFT_CHECK_INTERVAL = 20;

    private final Map<String, SceneInstance> instances = new ConcurrentHashMap<>();
    private final List<SceneListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> lastResyncRequest = new ConcurrentHashMap<>();
    /** 最近一次已同步给玩家的维度，用于兜底发现漏掉的换维度事件。 */
    private final Map<UUID, String> lastDimensionByPlayer = new ConcurrentHashMap<>();

    private volatile MinecraftServer server;
    private volatile boolean lastGlobalEnabled = true;
    private boolean registered;

    private SceneInstanceService() {}

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 幂等注册 tick 与服务器生命周期回调。 */
    public void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            this.server = started;
            this.lastGlobalEnabled = isGlobalEnabled();
            LOGGER.info("API 移动场景实例服务已就绪（实例数量无上限）");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> shutdown());
    }

    /** 服务器停止：清空全部实例（实例是运行时状态，不落盘）。 */
    public void shutdown() {
        int count = instances.size();
        instances.clear();
        lastResyncRequest.clear();
        this.server = null;
        if (count > 0) {
            LOGGER.info("已清空 {} 个 API 移动场景实例（服务器停止）", count);
        }
    }

    public MinecraftServer server() {
        return server;
    }

    private void onServerTick(MinecraftServer tickingServer) {
        this.server = tickingServer;
        boolean globalEnabled = isGlobalEnabled();
        if (globalEnabled != lastGlobalEnabled) {
            lastGlobalEnabled = globalEnabled;
            LOGGER.info("移动场景全局开关切换为 {}，{}", globalEnabled, globalEnabled ? "重新下发实例" : "清空客户端实例");
            if (globalEnabled) {
                resyncAll();
            } else {
                for (ServerPlayer player : tickingServer.getPlayerList().getPlayers()) {
                    send(player, SceneInstancesS2C.clearAll());
                }
            }
            fire(listener -> listener.onInstancesResynced());
        }

        expireInstances(tickingServer);

        if (DRIFT_CHECK_INTERVAL > 0 && tickingServer.getTickCount() % DRIFT_CHECK_INTERVAL == 0) {
            for (ServerPlayer player : tickingServer.getPlayerList().getPlayers()) {
                checkDimensionDrift(player);
            }
        }
    }

    private void expireInstances(MinecraftServer tickingServer) {
        if (instances.isEmpty()) return;
        Map<String, ServerLevel> levels = new java.util.HashMap<>();
        for (ServerLevel level : tickingServer.getAllLevels()) {
            levels.put(level.dimension().location().toString(), level);
        }
        List<String> expired = null;
        for (SceneInstance instance : instances.values()) {
            ServerLevel level = levels.get(instance.dimensionKey());
            if (level == null) continue;
            if (instance.isExpired(level.getGameTime())) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(instance.id());
            }
        }
        if (expired == null) return;
        for (String id : expired) {
            removeInstance(id, "expired");
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public boolean isGlobalEnabled() {
        return ConfigManager.getInstance().getSceneMotionSettings().enabled;
    }

    public Optional<SceneInstance> get(String instanceId) {
        if (instanceId == null) return Optional.empty();
        return Optional.ofNullable(instances.get(instanceId));
    }

    public boolean exists(String instanceId) {
        return instanceId != null && instances.containsKey(instanceId);
    }

    /** 全部实例（按优先级与创建顺序排序）。 */
    public List<SceneInstance> all() {
        List<SceneInstance> snapshot = new ArrayList<>(instances.values());
        snapshot.sort(Comparator.comparingInt(SceneInstance::priority).thenComparingLong(SceneInstance::createdAtMillis));
        return snapshot;
    }

    public List<SceneInstance> byDimension(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) return List.of();
        return all().stream().filter(instance -> instance.belongsTo(dimensionKey)).toList();
    }

    public List<SceneInstance> byOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return List.of();
        return all().stream().filter(instance -> ownerId.equals(instance.ownerId())).toList();
    }

    public List<SceneInstance> byTag(String tag) {
        if (tag == null || tag.isBlank()) return List.of();
        return all().stream().filter(instance -> instance.hasTag(tag)).toList();
    }

    public int count() {
        return instances.size();
    }

    public int countIn(String dimensionKey) {
        if (dimensionKey == null || dimensionKey.isBlank()) return 0;
        int total = 0;
        for (SceneInstance instance : instances.values()) {
            if (instance.belongsTo(dimensionKey)) total++;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 注册实例；ID 已存在时返回 {@link SceneSpawnResult.SceneSpawnStatus#ALREADY_EXISTS}。
     * 维度取自 {@code spec.dimensionKey()}。
     */
    public SceneSpawnResult spawn(SceneInstanceSpec spec) {
        if (spec == null) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.INVALID_SPEC, "", "spec 为 null");
        }
        ServerLevel level = resolveLevelForSpec(spec);
        if (level == null) {
            return levelFailure(spec);
        }
        return spawn(level, spec, false);
    }

    /** 注册实例；维度取自传入的 {@code level}。ID 已存在时返回 ALREADY_EXISTS。 */
    public SceneSpawnResult spawn(ServerLevel level, SceneInstanceSpec spec) {
        return spawn(level, spec, false);
    }

    /** 注册或覆盖实例（幂等 upsert 语义）。 */
    public SceneSpawnResult upsert(ServerLevel level, SceneInstanceSpec spec) {
        return spawn(level, spec, true);
    }

    /** 注册或覆盖实例；维度取自 {@code spec.dimensionKey()}。 */
    public SceneSpawnResult upsert(SceneInstanceSpec spec) {
        if (spec == null) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.INVALID_SPEC, "", "spec 为 null");
        }
        ServerLevel level = resolveLevelForSpec(spec);
        if (level == null) {
            return levelFailure(spec);
        }
        return spawn(level, spec, true);
    }

    private ServerLevel resolveLevelForSpec(SceneInstanceSpec spec) {
        String dimensionKey = spec.dimensionKey();
        return dimensionKey == null || dimensionKey.isBlank() ? null : levelOf(dimensionKey);
    }

    /**
     * 审核 S-04：{@code spawn(spec)} / {@code upsert(spec)} 找不到维度时，
     * 旧实现只回一句 {@code 找不到维度: null}——既不区分「未指定维度」与「维度不存在」，
     * 也不提示正确的重载（文档与 {@link SceneInstanceSpec#dimensionKey()} 的 javadoc
     * 都把 dimension 写成可选项）。
     */
    private SceneSpawnResult levelFailure(SceneInstanceSpec spec) {
        String dimensionKey = spec.dimensionKey();
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.LEVEL_NOT_FOUND,
                    spec.id(),
                    "spec 未指定维度：请调用 spec.builder(...).dimension(\"<维度键>\")，"
                            + "或改用 spawn(ServerLevel, spec) / upsert(ServerLevel, spec)");
        }
        return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.LEVEL_NOT_FOUND,
                spec.id(),
                "维度不存在或未加载: " + dimensionKey
                        + "（可用 spawn(ServerLevel, spec) / upsert(ServerLevel, spec) 直接指定 level）");
    }

    private SceneSpawnResult spawn(ServerLevel level, SceneInstanceSpec spec, boolean allowReplace) {
        if (level == null) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.SERVER_UNAVAILABLE, "", "level 为 null");
        }
        if (spec == null) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.INVALID_SPEC, "", "spec 为 null");
        }
        Optional<String> error = spec.validationError();
        if (error.isPresent()) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.INVALID_SPEC, spec.id(), error.get());
        }

        SceneInstance previous = instances.get(spec.id());
        if (previous != null && !allowReplace) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.ALREADY_EXISTS, spec.id(),
                    "实例已存在；请用 upsert 或先 despawn");
        }
        // 全局关闭时不再接受"新"实例；但已经登记过的实例允许继续改参数（只是不下发），
        // 这样重新打开开关后它们能按最新参数回来，而不是被冻在关闭前的旧参数。
        if (!isGlobalEnabled() && previous == null) {
            return SceneSpawnResult.failure(SceneSpawnResult.SceneSpawnStatus.GLOBAL_DISABLED, spec.id(),
                    "sceneMotion.enabled=false，移动场景全局关闭");
        }

        SceneInstance instance = materialize(level, spec, previous, level.dimension().location().toString());
        instances.put(instance.id(), instance);
        broadcastUpsert(instance);

        SceneInstanceView view = instance;
        if (previous == null) {
            LOGGER.info("API 移动场景实例注册: id={}, asset={}, dim={}, hash={}, 当前实例数={}",
                    instance.id(), instance.assetKey(), instance.dimensionKey(),
                    shortHash(instance.assetHash()), instances.size());
            fire(listener -> listener.onInstanceSpawned(view));
        } else {
            LOGGER.info("API 移动场景实例更新: id={}, asset={}, hash={}, rev={}",
                    instance.id(), instance.assetKey(), shortHash(instance.assetHash()), instance.revision());
            fire(listener -> listener.onInstanceUpdated(view));
        }
        return SceneSpawnResult.ok(instance.id(), previous == null ? "已注册" : "已更新");
    }

    /** 回收实例。 */
    public boolean despawn(String instanceId) {
        return removeInstance(instanceId, "despawn");
    }

    /**
     * 重新下发一个实例（不改变任何参数）。
     *
     * <p>给"实例参数被直接改过、或客户端状态可疑"的场合用；正常路径下所有写操作都会自动推送。</p>
     */
    public boolean resync(String instanceId) {
        SceneInstance instance = instances.get(instanceId);
        if (instance == null) return false;
        broadcastUpsert(instance);
        return true;
    }

    /** 重新下发某维度的全部实例。 */
    public int resyncDimension(String dimensionKey) {
        int sent = 0;
        for (SceneInstance instance : byDimension(dimensionKey)) {
            broadcastUpsert(instance);
            sent++;
        }
        return sent;
    }

    /** 把实例的时间轴起点重置到该维度当前 gameTime（回到起始相位）。 */
    public boolean restart(String instanceId) {
        SceneInstance current = instances.get(instanceId);
        if (current == null) return false;
        ServerLevel level = levelOf(current.dimensionKey());
        if (level == null) return false;
        long now = level.getGameTime();
        return mutate(instanceId, builder -> builder.startGameTime(now));
    }

    /** 把实例迁移到另一个维度。 */
    public boolean moveToDimension(String instanceId, String dimensionKey) {
        SceneInstance current = instances.get(instanceId);
        if (current == null || dimensionKey == null || dimensionKey.isBlank()) return false;
        if (Objects.equals(current.dimensionKey(), dimensionKey)) return true;
        ServerLevel target = levelOf(dimensionKey);
        if (target == null) return false;
        // 先从旧维度清掉，再在新维度注册：否则客户端会同时保留两份。
        broadcastRemoval(instanceId);
        instances.remove(instanceId);
        // 审核 S-01：迁移实例过去只 broadcastRemoval 就直接 remove，不触发 onInstanceRemoved，
        // 第三方监听器的旧维度缓存/音源/计时器会泄漏。
        fire(listener -> listener.onInstanceRemoved(current, "dimension_changed"));
        return spawn(target, current.spec(), true).isSuccess();
    }

    /** 回收某归属方的全部实例，返回回收数量。 */
    public int despawnAll(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return 0;
        return despawnMatching(instance -> ownerId.equals(instance.ownerId()));
    }

    /** 回收带某标签的全部实例。 */
    public int despawnByTag(String tag) {
        if (tag == null || tag.isBlank()) return 0;
        return despawnMatching(instance -> instance.hasTag(tag));
    }

    /** 按谓词批量回收，返回回收数量。 */
    public int despawnMatching(Predicate<SceneInstanceView> predicate) {
        if (predicate == null) return 0;
        List<String> ids = new ArrayList<>();
        for (SceneInstance instance : instances.values()) {
            if (predicate.test(instance)) ids.add(instance.id());
        }
        int removed = 0;
        for (String id : ids) {
            if (removeInstance(id, "despawn")) removed++;
        }
        return removed;
    }

    /** 回收当前维度的全部实例。 */
    public int despawnInDimension(String dimensionKey) {
        return despawnMatching(instance -> instance.belongsTo(dimensionKey));
    }

    /** 回收全部 API 实例。 */
    public int despawnAllInstances() {
        List<String> ids = new ArrayList<>(instances.keySet());
        int removed = 0;
        for (String id : ids) {
            if (removeInstance(id, "reset")) removed++;
        }
        return removed;
    }

    /**
     * 就地修改一个已有实例：以当前描述为基底应用 {@code mutation} 后重新注册。
     *
     * <p>这是所有 {@code SceneApi.setXxx(...)} 的实现基础；更新会保留时间轴（除非显式改
     * {@code startGameTime}）、保留创建时间并自增 revision。</p>
     *
     * @return 是否成功（实例不存在、描述非法或维度丢失都会返回 false）
     */
    public boolean mutate(String instanceId, Consumer<SceneInstanceSpec.Builder> mutation) {
        SceneInstance current = instances.get(instanceId);
        if (current == null || mutation == null) return false;
        SceneInstanceSpec.Builder builder = current.spec().toBuilder();
        try {
            mutation.accept(builder);
        } catch (RuntimeException e) {
            LOGGER.warn("场景实例修改回调抛异常: id={}", instanceId, e);
            return false;
        }
        SceneInstanceSpec updated = builder.build();
        ServerLevel level = levelOf(current.dimensionKey());
        if (level == null) return false;
        return spawn(level, updated, true).isSuccess();
    }

    private SceneInstance materialize(ServerLevel level, SceneInstanceSpec spec,
                                      SceneInstance previous, String fallbackDimension) {
        long now = level.getGameTime();
        // 显式传入 level 的调用以 level 的维度为准；只有 spawn(spec) / upsert(spec) 才会走到
        // "由 spec.dimensionKey() 解析出 level"，此时 fallbackDimension 就是那个维度。
        String dimensionKey = fallbackDimension;

        long base = spec.startGameTime() == SceneInstanceSpec.AUTO_START_GAME_TIME
                ? now : spec.startGameTime();
        long headStartTicks = Math.round(spec.headStartSeconds() * 20.0);
        long resolvedStart = base - headStartTicks;

        long pausedAt = -1L;
        if (spec.paused()) {
            pausedAt = previous != null && previous.paused() && previous.pausedAtGameTime() >= 0L
                    ? previous.pausedAtGameTime() : now;
        } else if (previous != null && previous.paused() && previous.pausedAtGameTime() >= 0L) {
            // 从暂停恢复：把暂停期间的时间补回时间轴，避免"解冻瞬间跳一大段"。
            resolvedStart += Math.max(0L, now - previous.pausedAtGameTime());
        }

        SceneInstanceSpec normalized = spec.toBuilder()
                .dimension(dimensionKey)
                .startGameTime(resolvedStart)
                .build();

        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(normalized.assetKey());
        String hash = descriptor != null && descriptor.isValid() ? descriptor.sha256() : "";

        long expireAt = normalized.durationTicks() > 0L ? now + normalized.durationTicks() : -1L;
        int revision = previous != null ? previous.revision() + 1 : 1;
        long createdAt = previous != null ? previous.createdAtMillis() : System.currentTimeMillis();
        return new SceneInstance(normalized, hash, revision, expireAt, pausedAt, createdAt);
    }

    private boolean removeInstance(String instanceId, String reason) {
        if (instanceId == null) return false;
        SceneInstance removed = instances.remove(instanceId);
        if (removed == null) return false;
        broadcastRemoval(removed.id());
        fire(listener -> listener.onInstanceRemoved(removed, reason));
        LOGGER.info("API 移动场景实例回收: id={}, reason={}, 剩余={}", removed.id(), reason, instances.size());
        return true;
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /** 玩家加入：先清空客户端残留，再全量重发该维度可见实例。 */
    public void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        lastDimensionByPlayer.put(player.getUUID(),
                player.serverLevel().dimension().location().toString());
        send(player, SceneInstancesS2C.clearAll());
        sendFullState(player);
    }

    /** 换维度：清空后重发目标维度。 */
    public void onPlayerChangeDimension(ServerPlayer player) {
        onPlayerJoin(player);
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId == null) return;
        lastResyncRequest.remove(playerId);
    }

    /** 处理客户端的重同步请求（带冷却）。 */
    public void handleResyncRequest(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer current = server != null ? server : player.getServer();
        long now = current != null ? current.getTickCount() : System.currentTimeMillis() / 50L;
        Long last = lastResyncRequest.get(player.getUUID());
        if (last != null && now - last < RESYNC_COOLDOWN_TICKS) {
            return;
        }
        lastResyncRequest.put(player.getUUID(), now);
        send(player, SceneInstancesS2C.clearAll());
        sendFullState(player);
        LOGGER.debug("已按客户端请求重同步 API 移动场景实例: player={}", player.getName().getString());
    }

    /** 向某维度内所有玩家强制全量重发。 */
    public void resyncLevel(ServerLevel level) {
        if (level == null) return;
        for (ServerPlayer player : level.players()) {
            send(player, SceneInstancesS2C.clearAll());
            sendFullState(player);
        }
    }

    /** 向所有玩家强制全量重发。 */
    public void resyncAll() {
        MinecraftServer current = server;
        if (current == null) return;
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            send(player, SceneInstancesS2C.clearAll());
            sendFullState(player);
        }
        fire(listener -> listener.onInstancesResynced());
    }

    private void sendFullState(ServerPlayer player) {
        if (player == null) return;
        if (!isGlobalEnabled()) return;
        String dimensionKey = player.serverLevel().dimension().location().toString();
        for (SceneInstance instance : byDimension(dimensionKey)) {
            if (!instance.isVisibleTo(player.getUUID())) continue;
            sendManifest(player, instance);
            send(player, SceneInstancesS2C.upsert(instance));
        }
    }

    private void broadcastUpsert(SceneInstance instance) {
        MinecraftServer current = server;
        if (current == null) return;
        if (!isGlobalEnabled()) return;
        ServerLevel level = levelOf(instance.dimensionKey());
        if (level == null) return;
        for (ServerPlayer player : level.players()) {
            if (!instance.isVisibleTo(player.getUUID())) continue;
            sendManifest(player, instance);
            send(player, SceneInstancesS2C.upsert(instance));
        }
    }

    private void broadcastRemoval(String instanceId) {
        MinecraftServer current = server;
        if (current == null) return;
        List<String> batch = new ArrayList<>(1);
        batch.add(instanceId);
        SceneInstancesS2C payload = new SceneInstancesS2C(List.of(), batch, false);
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            send(player, payload);
        }
    }

    private void sendManifest(ServerPlayer player, SceneInstance instance) {
        if (!instance.hasAsset()) return;
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(instance.assetKey());
        if (descriptor == null || !descriptor.isValid()) return;
        if (!Objects.equals(descriptor.sha256(), instance.assetHash())) return;
        SceneTransferService.getInstance().authorizeAsset(player, instance.assetKey(), descriptor);
        ServerPlayNetworking.send(player, new SceneAssetManifestS2C(instance.assetKey(), descriptor));
    }

    private static void send(ServerPlayer player, SceneInstancesS2C payload) {
        if (player == null || payload == null || payload.isEmpty()) return;
        if (!ServerPlayNetworking.canSend(player, SceneInstancesS2C.TYPE)) return;
        ServerPlayNetworking.send(player, payload);
    }

    private void checkDimensionDrift(ServerPlayer player) {
        if (player == null || player.serverLevel() == null) return;
        String dimensionKey = player.serverLevel().dimension().location().toString();
        String lastDimension = lastDimensionByPlayer.get(player.getUUID());
        if (!Objects.equals(dimensionKey, lastDimension)) {
            lastDimensionByPlayer.put(player.getUUID(), dimensionKey);
            send(player, SceneInstancesS2C.clearAll());
            sendFullState(player);
        }
    }

    // ------------------------------------------------------------------
    // 资产热更新
    // ------------------------------------------------------------------

    /**
     * 资产发布/热更新：刷新所有引用该资产键的实例哈希并重新下发。
     */
    public void onAssetPublished(String assetKey, SceneAssetDescriptor descriptor) {
        if (assetKey == null || descriptor == null || !descriptor.isValid()) return;
        fire(listener -> listener.onAssetPublished(assetKey, descriptor));
        int touched = 0;
        for (SceneInstance instance : instances.values()) {
            if (!assetKey.equals(instance.assetKey())) continue;
            if (Objects.equals(descriptor.sha256(), instance.assetHash())) continue;
            SceneInstance refreshed = instance.withAssetHash(descriptor.sha256());
            instances.put(refreshed.id(), refreshed);
            broadcastUpsert(refreshed);
            fire(listener -> listener.onInstanceUpdated(refreshed));
            touched++;
        }
        if (touched > 0) {
            LOGGER.info("资产 {} 热更新已推送到 {} 个 API 场景实例", assetKey, touched);
        }
    }

    // ------------------------------------------------------------------
    // 监听器与诊断
    // ------------------------------------------------------------------

    public void addListener(SceneListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(SceneListener listener) {
        listeners.remove(listener);
    }

    /** 供 {@link SceneRuntimeCoordinator} 转发"地图级场景启动"。 */
    public void fireMapSceneStarted(net.minecraft.server.level.ServerLevel level, String mapKey) {
        fire(listener -> listener.onMapSceneStarted(level, mapKey));
    }

    /** 供 {@link SceneRuntimeCoordinator} 转发"地图级场景停止"。 */
    public void fireMapSceneStopped(net.minecraft.server.level.ServerLevel level, String mapKey) {
        fire(listener -> listener.onMapSceneStopped(level, mapKey));
    }

    private void fire(Consumer<SceneListener> callback) {
        for (SceneListener listener : listeners) {
            try {
                callback.accept(listener);
            } catch (RuntimeException e) {
                LOGGER.warn("场景事件监听器抛异常（已隔离）", e);
            }
        }
    }

    /** 诊断快照。 */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("count", instances.size());
        root.addProperty("globalEnabled", isGlobalEnabled());
        JsonArray array = new JsonArray();
        for (SceneInstance instance : all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", instance.id());
            entry.addProperty("owner", instance.ownerId());
            entry.addProperty("dimension", instance.dimensionKey());
            entry.addProperty("assetKey", instance.assetKey());
            entry.addProperty("assetHash", instance.assetHash());
            entry.addProperty("startGameTime", instance.startGameTime());
            entry.addProperty("timeScale", instance.timeScale());
            entry.addProperty("paused", instance.paused());
            entry.addProperty("priority", instance.priority());
            entry.addProperty("revision", instance.revision());
            entry.addProperty("expireAtGameTime", instance.expireAtGameTime());
            entry.addProperty("visibleToAll", instance.visibleToAll());
            entry.add("anchor", instance.anchor().toJson());
            JsonArray tags = new JsonArray();
            instance.tags().forEach(tags::add);
            entry.add("tags", tags);
            entry.add("profile", instance.profile().toJson());
            array.add(entry);
        }
        root.add("instances", array);
        return root;
    }

    /** 可读的单行摘要（命令输出用）。 */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (SceneInstance instance : all()) {
            lines.add("§b" + instance.id() + " §7[" + instance.ownerId() + "] §f"
                    + instance.assetKey() + " §7dim=" + instance.dimensionKey()
                    + " rev=" + instance.revision()
                    + (instance.paused() ? " §e已暂停" : "")
                    + (instance.durationTicks() > 0 ? " §7剩余=" + instance.remainingTicks(currentGameTime(instance)) + "t" : "")
                    + (instance.hasAsset() ? "" : " §c(无资产)"));
        }
        return lines;
    }

    private long currentGameTime(SceneInstance instance) {
        ServerLevel level = levelOf(instance.dimensionKey());
        return level != null ? level.getGameTime() : 0L;
    }

    private ServerLevel levelOf(String dimensionKey) {
        MinecraftServer current = server;
        if (current == null || dimensionKey == null || dimensionKey.isBlank()) return null;
        ResourceLocation location = ResourceLocation.tryParse(dimensionKey);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return current.getLevel(key);
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) return "NONE";
        return hash.substring(0, Math.min(8, hash.length()));
    }

    /** 供诊断/命令使用的不可变快照。 */
    public Collection<SceneInstance> snapshot() {
        return List.copyOf(instances.values());
    }
}
