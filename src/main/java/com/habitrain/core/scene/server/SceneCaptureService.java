package com.habitrain.core.scene.server;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.network.SceneAssetBuildProgressS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 场景资产捕获服务（两阶段分预算捕获：主线程时间预算快照 -> 单后台线程压缩哈希落盘）。
 */
public final class SceneCaptureService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneCaptureService.class.getSimpleName());

    public static final int MAX_AXIS_LENGTH = SceneLimits.MAX_AXIS_LENGTH;
    public static final int MAX_NON_EMPTY_SECTIONS = SceneLimits.MAX_NON_EMPTY_SECTIONS;
    public static final long MAX_NON_AIR_BLOCKS = SceneLimits.MAX_NON_AIR_BLOCKS;
    public static final int SECTIONS_PER_TICK = 4;
    public static final long MAX_TICK_TIME_NANOS = 2_000_000L; // 2 ms

    private static final SceneCaptureService INSTANCE = new SceneCaptureService();

    public static SceneCaptureService getInstance() {
        return INSTANCE;
    }

    private ExecutorService backgroundExecutor = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HabiTrain-SceneCapture-Worker");
        t.setDaemon(true);
        return t;
        });
    }

    private static final class ActiveCaptureTask {
        final String mapKey;
        final UUID requesterPlayerId;
        final ServerLevel level;
        final SceneBounds bounds;
        final List<SectionPos> pendingSections = new ArrayList<>();
        final List<SceneAssetCodec.SectionData> capturedSections = new ArrayList<>();
        int totalSectionsToScan;
        long totalNonAirBlocks = 0;
        int currentSectionIndex = 0;
        long lastProgressGameTime = Long.MIN_VALUE;

        ActiveCaptureTask(String mapKey, UUID requesterPlayerId, ServerLevel level, SceneBounds bounds) {
            this.mapKey = mapKey;
            this.requesterPlayerId = requesterPlayerId;
            this.level = level;
            this.bounds = bounds;
        }
    }

    private ActiveCaptureTask currentTask = null;

    private SceneCaptureService() {}

    /**
     * 请求开始捕获指定地图的场景资产。
     */
    public synchronized boolean requestCapture(ServerLevel level, String mapKey, SceneBounds bounds, ServerPlayer requester) {
        if (requester == null || level == null) return false;
        if (currentTask != null) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c已有场景捕获任务正在进行中"));
            return false;
        }

        if (bounds == null || bounds.isEmpty()) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c场景源范围为空，请先用配置器选定范围"));
            return false;
        }

        if (bounds.sizeX() > MAX_AXIS_LENGTH || bounds.sizeY() > MAX_AXIS_LENGTH || bounds.sizeZ() > MAX_AXIS_LENGTH) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c选区超过单轴最大限制 (" + MAX_AXIS_LENGTH + " 格)"));
            return false;
        }

        for (int cx = bounds.minSectionX(); cx <= bounds.maxSectionX(); cx++) {
            for (int cz = bounds.minSectionZ(); cz <= bounds.maxSectionZ(); cz++) {
                if (!level.hasChunk(cx, cz)) {
                    requester.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c选区包含未加载区块: " + cx + ", " + cz + "，请先加载完整区域"));
                    return false;
                }
            }
        }

        String normalizedMapKey = mapKey == null || mapKey.isBlank() ? "__default__" : mapKey.trim();

        ActiveCaptureTask task = new ActiveCaptureTask(normalizedMapKey, requester.getUUID(), level, bounds);
        int minSecX = bounds.minSectionX();
        int minSecY = bounds.minSectionY();
        int minSecZ = bounds.minSectionZ();
        int maxSecX = bounds.maxSectionX();
        int maxSecY = bounds.maxSectionY();
        int maxSecZ = bounds.maxSectionZ();

        for (int sx = minSecX; sx <= maxSecX; sx++) {
            for (int sy = minSecY; sy <= maxSecY; sy++) {
                for (int sz = minSecZ; sz <= maxSecZ; sz++) {
                    task.pendingSections.add(SectionPos.of(sx, sy, sz));
                }
            }
        }

        task.totalSectionsToScan = task.pendingSections.size();
        this.currentTask = task;
        LOGGER.info("开始捕获场景资产: mapKey={}, 范围={}, 总Section数={}",
                normalizedMapKey, bounds, task.totalSectionsToScan);
        sendProgress(requester.getServer(), task, "CAPTURING", 0.0f,
                "正在捕获方块数据 (0/" + task.totalSectionsToScan + ")");
        return true;
    }

    public synchronized boolean startCapture(ServerPlayer requester, String mapKey) {
        if (requester == null) return false;
        if (currentTask != null) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c已有场景捕获任务正在进行中"));
            return false;
        }

        ServerLevel level = requester.serverLevel();
        String requestedMapKey = mapKey == null ? "" : mapKey.trim();
        String currentMapKey = com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE
                .resolve(level).mapKey();
        var runtimeState = SceneRuntimeCoordinator.getInstance().getRuntimeState(level);
        if (runtimeState != null && runtimeState.isActive()
                && runtimeState.getMapKey() != null && !runtimeState.getMapKey().isBlank()) {
            currentMapKey = runtimeState.getMapKey();
        }
        var toolSession = SceneSelectionSessionManager.getInstance().getSession(requester.getUUID());
        boolean matchesValidatedToolSession = toolSession != null
                && level.dimension().location().toString().equals(toolSession.getDimension())
                && requestedMapKey.equals(toolSession.getMapKey());
        if (!requestedMapKey.equals(currentMapKey) && !matchesValidatedToolSession) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c构建请求地图与服务端当前地图不一致"));
            return false;
        }
        if (!requester.getMainHandItem().is(com.habitrain.core.scene.item.HabiAdminItems.SCENE_CONFIGURATOR)
                && !requester.getOffhandItem().is(com.habitrain.core.scene.item.HabiAdminItems.SCENE_CONFIGURATOR)) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c生成资产时必须手持移动场景配置器"));
            return false;
        }
        currentMapKey = requestedMapKey;
        SceneProfile profile = ConfigManager.getInstance().getSceneMotionSettings().getProfile(currentMapKey);
        if (!profile.getDimension().equals(level.dimension().location().toString())) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c当前维度与场景 profile 维度不一致"));
            return false;
        }
        SceneBounds bounds = profile.getSourceBounds();
        if (bounds.isEmpty()) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c场景源范围为空，请先用配置器选定范围"));
            return false;
        }

        if (bounds.sizeX() > MAX_AXIS_LENGTH || bounds.sizeY() > MAX_AXIS_LENGTH || bounds.sizeZ() > MAX_AXIS_LENGTH) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c选区超过单轴最大限制 (" + MAX_AXIS_LENGTH + " 格)"));
            return false;
        }

        return requestCapture(level, currentMapKey, bounds, requester);
    }

    /**
     * 在主服务器 tick 循环中调用，受预算限制推进捕获。
     */
    public synchronized void tick(MinecraftServer server) {
        if (currentTask == null || server == null) return;
        ActiveCaptureTask task = currentTask;

        long startTime = System.nanoTime();
        int processedThisTick = 0;

        while (task.currentSectionIndex < task.pendingSections.size()) {
            if (processedThisTick >= SECTIONS_PER_TICK || (System.nanoTime() - startTime) >= MAX_TICK_TIME_NANOS) {
                break;
            }

            SectionPos secPos = task.pendingSections.get(task.currentSectionIndex);
            task.currentSectionIndex++;
            processedThisTick++;

            SceneAssetCodec.SectionData sectionData = captureSection(task.level, task.bounds, secPos);
            if (sectionData != null) {
                task.capturedSections.add(sectionData);
                task.totalNonAirBlocks += countNonAir(sectionData);
                if (task.totalNonAirBlocks > MAX_NON_AIR_BLOCKS) {
                    failTask(server, task, "非空气方块数量超过上限 (" + MAX_NON_AIR_BLOCKS + ")");
                    currentTask = null;
                    return;
                }
                if (task.capturedSections.size() > MAX_NON_EMPTY_SECTIONS) {
                    failTask(server, task, "非空 Section 数量超过上限 (" + MAX_NON_EMPTY_SECTIONS + ")");
                    currentTask = null;
                    return;
                }
            }
        }

        float progress = (float) task.currentSectionIndex / (float) Math.max(1, task.totalSectionsToScan);
        long gameTime = server.getTickCount();
        if (task.lastProgressGameTime == Long.MIN_VALUE || gameTime - task.lastProgressGameTime >= 5
                || task.currentSectionIndex >= task.pendingSections.size()) {
            task.lastProgressGameTime = gameTime;
            sendProgress(server, task, "CAPTURING", progress * 0.7f,
                    "正在捕获方块数据 (" + task.currentSectionIndex + "/" + task.totalSectionsToScan + ")");
        }

        if (task.currentSectionIndex >= task.pendingSections.size()) {
            // 第一阶段完成，转入后台线程进行 GZIP 压缩与写盘
            ActiveCaptureTask finishedTask = this.currentTask;
            this.currentTask = null;
            dispatchBackgroundSave(server, finishedTask);
        }
    }

    private SceneAssetCodec.SectionData captureSection(ServerLevel level, SceneBounds bounds, SectionPos secPos) {
        ChunkPos chunkPos = secPos.chunk();
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            // 未加载区块视为空气或跳过
            return null;
        }

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int secIndex = chunk.getSectionIndexFromSectionY(secPos.getY());
        if (secIndex < 0 || secIndex >= chunk.getSections().length) {
            return null;
        }

        LevelChunkSection section = chunk.getSections()[secIndex];
        if (section == null || section.hasOnlyAir()) {
            return null;
        }

        Map<String, Short> paletteMap = new LinkedHashMap<>();
        List<String> paletteList = new ArrayList<>();
        short[] blockIndices = new short[4096];
        boolean hasNonAir = false;

        paletteMap.put("minecraft:air", (short) 0);
        paletteList.add("minecraft:air");

        int secMinX = secPos.minBlockX();
        int secMinY = secPos.minBlockY();
        int secMinZ = secPos.minBlockZ();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = secMinX + x;
                    int worldY = secMinY + y;
                    int worldZ = secMinZ + z;
                    int flatIdx = (y << 8) | (z << 4) | x;

                    if (worldX < bounds.minX() || worldX >= bounds.maxX()
                            || worldY < bounds.minY() || worldY >= bounds.maxY()
                            || worldZ < bounds.minZ() || worldZ >= bounds.maxZ()) {
                        blockIndices[flatIdx] = 0;
                        continue;
                    }

                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir()) {
                        blockIndices[flatIdx] = 0;
                        continue;
                    }

                    hasNonAir = true;
                    String stateStr = serializeBlockState(state);
                    short paletteIdx = paletteMap.computeIfAbsent(stateStr, k -> {
                        short next = (short) paletteList.size();
                        paletteList.add(k);
                        return next;
                    });
                    blockIndices[flatIdx] = paletteIdx;
                }
            }
        }

        if (!hasNonAir) {
            return null;
        }

        byte[] skyLight = new byte[2048];
        byte[] blockLight = new byte[2048];
        DataLayer skyLayer = level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(secPos);
        if (skyLayer != null && skyLayer.getData() != null && skyLayer.getData().length == 2048) {
            System.arraycopy(skyLayer.getData(), 0, skyLight, 0, 2048);
        } else {
            Arrays.fill(skyLight, (byte) 0xFF); // 默认满亮度天空光
        }

        DataLayer blockLayer = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(secPos);
        if (blockLayer != null && blockLayer.getData() != null && blockLayer.getData().length == 2048) {
            System.arraycopy(blockLayer.getData(), 0, blockLight, 0, 2048);
        }

        int relX = secPos.getX() - bounds.minSectionX();
        int relY = secPos.getY() - bounds.minSectionY();
        int relZ = secPos.getZ() - bounds.minSectionZ();

        return new SceneAssetCodec.SectionData(relX, relY, relZ, paletteList, blockIndices, skyLight, blockLight);
    }

    private static long countNonAir(SceneAssetCodec.SectionData section) {
        long count = 0L;
        for (short index : section.blockIndices) if (index > 0) count++;
        return count;
    }

    private void dispatchBackgroundSave(MinecraftServer server, ActiveCaptureTask task) {
        sendProgress(server, task, "COMPRESSING", 0.75f, "正在编码与压缩资产...");

        ensureExecutor().execute(() -> {
            try {
                int dataVersion = net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();
                String dimension = task.level.dimension().location().toString();
                String fingerprint = com.habitrain.core.scene.asset.SceneRegistryFingerprint.calculate();

                SceneAssetCodec.AssetData assetData = new SceneAssetCodec.AssetData(
                        dataVersion, dimension, task.bounds, fingerprint, task.capturedSections);

                long uncompressedSize = SceneAssetCodec.estimateUncompressedSize(assetData);
                if (uncompressedSize > SceneAssetCodec.MAX_UNCOMPRESSED_BYTES) {
                    server.execute(() -> failTask(server, task, "解压后资产超过 128 MiB 上限"));
                    return;
                }

                byte[] compressed = SceneAssetCodec.encode(assetData);
                if (compressed.length > SceneTransferService.MAX_FILE_SIZE) {
                    server.execute(() -> failTask(server, task, "压缩后资产超过 64 MiB 传输上限"));
                    return;
                }
                String sha256 = SceneAssetCodec.calculateSha256(compressed);

                SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                        sha256,
                        uncompressedSize,
                        compressed.length,
                        task.capturedSections.size(),
                        dataVersion,
                        fingerprint,
                        System.currentTimeMillis()
                );

                boolean saved = SceneAssetStore.getInstance().saveAsset(task.mapKey, compressed, descriptor);
                if (saved) {
                    server.execute(() -> {
                        sendProgress(server, task, "COMPLETED", 1.0f,
                                "资产生成完成！SHA-256: " + descriptor.shortHash() + " (" + task.capturedSections.size() + " sections)");
                        // 通知运行时协调器发布新资产
                        SceneRuntimeCoordinator.getInstance().onAssetPublished(server, task.mapKey, descriptor);
                    });
                } else {
                    server.execute(() -> failTask(server, task, "保存资产文件到磁盘失败"));
                }
            } catch (Throwable t) {
                LOGGER.error("后台压缩场景资产失败", t);
                server.execute(() -> failTask(server, task, "压缩资产异常: " + t.getMessage()));
            }
        });
    }

    private void failTask(MinecraftServer server, ActiveCaptureTask task, String reason) {
        LOGGER.warn("场景捕获任务失败: mapKey={}, 原因={}", task.mapKey, reason);
        sendProgress(server, task, "FAILED", 0.0f, "失败: " + reason);
    }

    private void sendProgress(MinecraftServer server, ActiveCaptureTask task, String state, float progress, String msg) {
        SceneAssetBuildProgressS2C payload = new SceneAssetBuildProgressS2C(
                task.mapKey, state, progress, task.currentSectionIndex, task.totalSectionsToScan, msg);

        ServerPlayer player = server.getPlayerList().getPlayer(task.requesterPlayerId);
        if (player != null) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private synchronized ExecutorService ensureExecutor() {
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = createExecutor();
        }
        return backgroundExecutor;
    }

    public synchronized void shutdown() {
        currentTask = null;
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
    }

    public static String serializeBlockState(BlockState state) {
        if (state == null || state.isAir()) return "minecraft:air";
        ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder sb = new StringBuilder(loc.toString());
        Collection<Property<?>> properties = state.getProperties();
        if (!properties.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> prop : properties) {
                if (!first) sb.append(',');
                sb.append(prop.getName()).append('=').append(getPropertyValueName(prop, state));
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropertyValueName(Property<T> prop, BlockState state) {
        return prop.getName(state.getValue(prop));
    }
}
