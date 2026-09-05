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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import com.habitrain.core.api.scene.compat.SceneBlockCaptureAdapter;
import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneCaptureContext;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.compat.SceneBlockCaptureAdapterRegistry;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    public static final int MAX_CHUNK_TICKETS = 16;
    public static final int MAX_MISSING_CHUNK_WAIT_TICKS = 200;

    private static final TicketType<ChunkPos> SCENE_CAPTURE_TICKET = TicketType.create(
            "habitrain_scene_capture",
            Comparator.comparingLong(ChunkPos::toLong)
    );

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
        final String toolSessionId;
        final ServerLevel level;
        final SceneBounds bounds;
        final SceneBounds haloBounds;
        final List<SectionPos> pendingSections = new ArrayList<>();
        final List<ChunkPos> pendingSectionChunks = new ArrayList<>();
        final List<SceneAssetCodec.SectionData> capturedSections = new ArrayList<>();
        final List<SceneBlockPayloadEntry> capturedPayloads = new ArrayList<>();
        final SceneCaptureAccounting accounting = new SceneCaptureAccounting();
        CaptureTicketWindow<ChunkPos> ticketWindow;
        int totalSectionsToScan;
        long totalNonAirBlocks = 0;
        int currentSectionIndex = 0;
        long lastProgressGameTime = Long.MIN_VALUE;

        ActiveCaptureTask(String mapKey, UUID requesterPlayerId, String toolSessionId,
                          ServerLevel level, SceneBounds bounds, SceneBounds haloBounds) {
            this.mapKey = mapKey;
            this.requesterPlayerId = requesterPlayerId;
            this.toolSessionId = toolSessionId;
            this.level = level;
            this.bounds = bounds;
            this.haloBounds = haloBounds != null ? haloBounds : bounds;
        }
    }

    private ActiveCaptureTask currentTask = null;

    private SceneCaptureService() {}

    /**
     * 请求开始捕获指定地图的场景资产。
     */
    public synchronized boolean requestCapture(ServerLevel level, String mapKey, SceneBounds bounds, ServerPlayer requester) {
        if (requester == null || level == null) return false;
        if (!requester.hasPermissions(2)) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c需要 OP2 权限才能生成场景资产"));
            return false;
        }
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

        String normalizedMapKey = mapKey == null || mapKey.isBlank() ? "__default__" : mapKey.trim();
        SceneSelectionSessionManager selectionManager = SceneSelectionSessionManager.getInstance();
        selectionManager.selectEditorMap(requester.getUUID(), normalizedMapKey);
        String toolSessionId = selectionManager.getEditorSessionId(requester.getUUID());
        if (toolSessionId.isBlank()) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c无法建立场景工具会话，捕获未开始"));
            return false;
        }

        SceneBounds haloBounds = bounds.inflate(1);
        ActiveCaptureTask task = new ActiveCaptureTask(
                normalizedMapKey, requester.getUUID(), toolSessionId, level, bounds, haloBounds);
        int minSecX = haloBounds.minSectionX();
        int minSecY = haloBounds.minSectionY();
        int minSecZ = haloBounds.minSectionZ();
        int maxSecX = haloBounds.maxSectionX();
        int maxSecY = haloBounds.maxSectionY();
        int maxSecZ = haloBounds.maxSectionZ();

        // Keep every vertical section for one chunk adjacent so a ticket can be
        // released as soon as that chunk has been scanned.
        for (int sx = minSecX; sx <= maxSecX; sx++) {
            for (int sz = minSecZ; sz <= maxSecZ; sz++) {
                for (int sy = minSecY; sy <= maxSecY; sy++) {
                    SectionPos sectionPos = SectionPos.of(sx, sy, sz);
                    task.pendingSections.add(sectionPos);
                    task.pendingSectionChunks.add(sectionPos.chunk());
                }
            }
        }

        task.totalSectionsToScan = task.pendingSections.size();
        task.ticketWindow = createTicketWindow(task);
        try {
            task.ticketWindow.alignToSection(0);
        } catch (Throwable ticketFailure) {
            LOGGER.error("无法为场景捕获申请临时区块票: mapKey={}", normalizedMapKey, ticketFailure);
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c无法加载场景选区区块，捕获未开始: " + safeMessage(ticketFailure)));
            return false;
        }
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
        String requestedAssetKey = mapKey == null ? "" : mapKey.trim();
        String requestedMapKey = com.habitrain.core.scene.model.SceneBackgroundKey
                .mapKeyFromAssetKey(requestedAssetKey);
        String requestedBackgroundId = com.habitrain.core.scene.model.SceneBackgroundKey
                .backgroundIdFromAssetKey(requestedAssetKey);
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
        String editorMapKey = SceneSelectionSessionManager.getInstance().getEditorMapKey(requester.getUUID());
        boolean matchesEditorMap = requestedMapKey.equals(
                com.habitrain.core.scene.model.SceneBackgroundKey.mapKeyFromAssetKey(editorMapKey));
        if (!requestedMapKey.equals(currentMapKey) && !matchesValidatedToolSession && !matchesEditorMap) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c构建请求地图与服务端当前地图不一致"));
            return false;
        }
        if (!requester.getMainHandItem().is(com.habitrain.core.scene.item.HabiAdminItems.SCENE_CONFIGURATOR)
                && !requester.getOffhandItem().is(com.habitrain.core.scene.item.HabiAdminItems.SCENE_CONFIGURATOR)) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c生成资产时必须手持移动场景配置器"));
            return false;
        }
        currentMapKey = requestedMapKey;
        var settings = ConfigManager.getInstance().getSceneMotionSettings();
        boolean configuredBackground = settings.getResolvedBackgrounds(currentMapKey).stream()
                .anyMatch(background -> background.id().equals(requestedBackgroundId));
        if (!configuredBackground) {
            requester.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c动态背景不存在或尚未保存"));
            return false;
        }
        SceneProfile profile = settings.getBackgroundProfile(currentMapKey, requestedBackgroundId);
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

        return requestCapture(level, requestedAssetKey, bounds, requester);
    }

    /**
     * 在主服务器 tick 循环中调用，受预算限制推进捕获。
     */
    public synchronized void tick(MinecraftServer server) {
        if (currentTask == null || server == null) return;
        ActiveCaptureTask task = currentTask;

        if (server.getPlayerList().getPlayer(task.requesterPlayerId) == null) {
            terminateCurrentTask(server, task, "发起捕获的管理员已离线");
            return;
        }

        long startTime = System.nanoTime();
        int processedThisTick = 0;

        try {
            while (task.currentSectionIndex < task.pendingSections.size()) {
                if (processedThisTick >= SECTIONS_PER_TICK || (System.nanoTime() - startTime) >= MAX_TICK_TIME_NANOS) {
                    break;
                }

                task.ticketWindow.alignToSection(task.currentSectionIndex);
                SectionPos secPos = task.pendingSections.get(task.currentSectionIndex);
                SectionCaptureResult result = captureSection(task.level, task.bounds, task.haloBounds, secPos, task.capturedPayloads);
                task.accounting.record(result.outcome());

                if (result.outcome() == SceneCaptureAccounting.SectionOutcome.MISSING) {
                    if (task.accounting.consecutiveMissingTicks() >= MAX_MISSING_CHUNK_WAIT_TICKS) {
                        ChunkPos missing = secPos.chunk();
                        terminateCurrentTask(server, task,
                                "区块 " + missing.x + ", " + missing.z + " 在临时加载票生效后仍未加载，捕获已超时");
                    }
                    break;
                }

                task.currentSectionIndex++;
                processedThisTick++;

                SceneAssetCodec.SectionData sectionData = result.sectionData();
                if (sectionData != null) {
                    task.capturedSections.add(sectionData);
                    task.totalNonAirBlocks += countNonAir(task.bounds, secPos, sectionData);
                    if (task.totalNonAirBlocks > MAX_NON_AIR_BLOCKS) {
                        terminateCurrentTask(server, task, "非空气方块数量超过上限 (" + MAX_NON_AIR_BLOCKS + ")");
                        return;
                    }
                    if (task.capturedSections.size() > MAX_NON_EMPTY_SECTIONS) {
                        terminateCurrentTask(server, task, "非空 Section 数量超过上限 (" + MAX_NON_EMPTY_SECTIONS + ")");
                        return;
                    }
                }
            }
        } catch (Throwable captureFailure) {
            LOGGER.error("推进场景捕获时发生异常: mapKey={}", task.mapKey, captureFailure);
            terminateCurrentTask(server, task, "捕获异常: " + safeMessage(captureFailure));
            return;
        }

        if (currentTask != task) return;

        float progress = (float) task.currentSectionIndex / (float) Math.max(1, task.totalSectionsToScan);
        long gameTime = server.getTickCount();
        if (task.lastProgressGameTime == Long.MIN_VALUE || gameTime - task.lastProgressGameTime >= 5
                || task.currentSectionIndex >= task.pendingSections.size()) {
            task.lastProgressGameTime = gameTime;
            sendProgress(server, task, "CAPTURING", progress * 0.7f,
                    "正在捕获方块数据 (" + task.currentSectionIndex + "/" + task.totalSectionsToScan
                            + "，非空 " + task.accounting.capturedSections()
                            + "，空 " + task.accounting.emptySections()
                            + "，高度外 " + task.accounting.outsideBuildHeightSections() + ")");
        }

        if (task.currentSectionIndex >= task.pendingSections.size()) {
            if (!task.accounting.isComplete(task.totalSectionsToScan)) {
                terminateCurrentTask(server, task,
                        "Section 完整性校验失败: 已确认 " + task.accounting.processedSections()
                                + "/" + task.totalSectionsToScan);
                return;
            }
            // 第一阶段完成，转入后台线程进行 GZIP 压缩与写盘
            ActiveCaptureTask finishedTask = this.currentTask;
            this.currentTask = null;
            releaseTickets(finishedTask);
            dispatchBackgroundSave(server, finishedTask);
        }
    }

    private SectionCaptureResult captureSection(ServerLevel level, SceneBounds bounds, SceneBounds haloBounds,
                                                SectionPos secPos, List<SceneBlockPayloadEntry> outPayloads) {
        ChunkPos chunkPos = secPos.chunk();
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return SectionCaptureResult.missing();
        }

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int secIndex = chunk.getSectionIndexFromSectionY(secPos.getY());
        if (secIndex < 0 || secIndex >= chunk.getSections().length) {
            return SectionCaptureResult.outsideBuildHeight();
        }

        LevelChunkSection section = chunk.getSections()[secIndex];
        if (section == null || section.hasOnlyAir()) {
            return SectionCaptureResult.empty();
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

                    boolean inVisible = bounds.contains(worldX, worldY, worldZ);
                    boolean inHalo = haloBounds.contains(worldX, worldY, worldZ);

                    if (!inHalo) {
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

                    if (inVisible && outPayloads != null) {
                        BlockPos worldPos = new BlockPos(worldX, worldY, worldZ);
                        BlockEntity blockEntity = level.getBlockEntity(worldPos);
                        SceneBlockCaptureAdapter adapter = SceneBlockCaptureAdapterRegistry.getInstance().findAdapter(state, blockEntity);
                        if (adapter != null) {
                            try {
                                int localX = worldX - bounds.minX();
                                int localY = worldY - bounds.minY();
                                int localZ = worldZ - bounds.minZ();
                                SceneCaptureContext ctx = new SceneCaptureContext(
                                        level, worldPos, new BlockPos(localX, localY, localZ), state, blockEntity, bounds);
                                SceneRenderPayload payload = adapter.captureVisualData(ctx);
                                if (payload != null && !payload.isEmpty()) {
                                    payload.validate();
                                    outPayloads.add(new SceneBlockPayloadEntry(
                                            localX, localY, localZ, adapter.adapterId(), adapter.dataVersion(), payload));
                                }
                            } catch (Throwable t) {
                                LOGGER.warn("方块适配器捕获视觉数据失败: adapter={}, pos={}", adapter.adapterId(), worldPos, t);
                                throw new IllegalStateException(
                                        "方块适配器捕获失败: " + adapter.adapterId() + " @ " + worldPos, t);
                            }
                        }
                    }
                }
            }
        }

        if (!hasNonAir) {
            return SectionCaptureResult.empty();
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

        return SectionCaptureResult.captured(
                new SceneAssetCodec.SectionData(relX, relY, relZ, paletteList, blockIndices, skyLight, blockLight));
    }

    private record SectionCaptureResult(
            SceneCaptureAccounting.SectionOutcome outcome,
            SceneAssetCodec.SectionData sectionData
    ) {
        static SectionCaptureResult captured(SceneAssetCodec.SectionData data) {
            return new SectionCaptureResult(SceneCaptureAccounting.SectionOutcome.CAPTURED, data);
        }

        static SectionCaptureResult empty() {
            return new SectionCaptureResult(SceneCaptureAccounting.SectionOutcome.EMPTY, null);
        }

        static SectionCaptureResult outsideBuildHeight() {
            return new SectionCaptureResult(SceneCaptureAccounting.SectionOutcome.OUTSIDE_BUILD_HEIGHT, null);
        }

        static SectionCaptureResult missing() {
            return new SectionCaptureResult(SceneCaptureAccounting.SectionOutcome.MISSING, null);
        }
    }

    private static long countNonAir(SceneBounds bounds, SectionPos secPos, SceneAssetCodec.SectionData section) {
        long count = 0L;
        int secMinX = secPos.minBlockX();
        int secMinY = secPos.minBlockY();
        int secMinZ = secPos.minBlockZ();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int flat = (y << 8) | (z << 4) | x;
                    if (section.blockIndices[flat] > 0 && bounds.contains(secMinX + x, secMinY + y, secMinZ + z)) {
                        count++;
                    }
                }
            }
        }
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
                        SceneAssetCodec.FORMAT_VERSION_V2,
                        dataVersion,
                        dimension,
                        task.bounds,
                        task.haloBounds,
                        fingerprint,
                        task.capturedSections,
                        task.capturedPayloads);

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

                boolean staged = SceneStagingService.getInstance().stage(
                        server, task.requesterPlayerId, task.mapKey,
                        task.level.dimension().location().toString(), task.toolSessionId,
                        compressed, descriptor);
                if (staged) {
                    LOGGER.info("场景资产已进入诊断暂存: mapKey={}, hash={}, sections={}",
                            task.mapKey, descriptor.shortHash(), task.capturedSections.size());
                } else {
                    server.execute(() -> failTask(server, task, "写入诊断暂存资产失败；旧正式资产未改变"));
                }
            } catch (Throwable t) {
                LOGGER.error("后台压缩场景资产失败", t);
                server.execute(() -> failTask(server, task, "压缩资产异常: " + t.getMessage()));
            }
        });
    }

    private CaptureTicketWindow<ChunkPos> createTicketWindow(ActiveCaptureTask task) {
        return new CaptureTicketWindow<>(task.pendingSectionChunks, MAX_CHUNK_TICKETS,
                new CaptureTicketWindow.TicketAccess<>() {
                    @Override
                    public void acquire(ChunkPos chunkPos) {
                        task.level.getChunkSource().addRegionTicket(
                                SCENE_CAPTURE_TICKET, chunkPos, 2, chunkPos);
                    }

                    @Override
                    public void release(ChunkPos chunkPos) {
                        task.level.getChunkSource().removeRegionTicket(
                                SCENE_CAPTURE_TICKET, chunkPos, 2, chunkPos);
                    }
                });
    }

    private void terminateCurrentTask(MinecraftServer server, ActiveCaptureTask task, String reason) {
        if (currentTask == task) currentTask = null;
        releaseTickets(task);
        failTask(server, task, reason);
    }

    private void releaseTickets(ActiveCaptureTask task) {
        if (task == null || task.ticketWindow == null) return;
        try {
            task.ticketWindow.close();
        } catch (Throwable releaseFailure) {
            LOGGER.error("释放场景捕获临时区块票失败: mapKey={}", task.mapKey, releaseFailure);
        }
    }

    /** Cancels a task owned by the given player and releases every held ticket. */
    public synchronized boolean cancelCapture(UUID requesterPlayerId, String reason) {
        ActiveCaptureTask task = currentTask;
        if (task == null || requesterPlayerId == null || !requesterPlayerId.equals(task.requesterPlayerId)) {
            return false;
        }
        MinecraftServer server = task.level.getServer();
        String resolvedReason = reason == null || reason.isBlank() ? "捕获已取消" : reason;
        if (server != null) {
            terminateCurrentTask(server, task, resolvedReason);
        } else {
            currentTask = null;
            releaseTickets(task);
            LOGGER.warn("场景捕获任务已取消: mapKey={}, 原因={}", task.mapKey, resolvedReason);
        }
        return true;
    }

    public void onPlayerDisconnect(UUID requesterPlayerId) {
        cancelCapture(requesterPlayerId, "发起捕获的管理员已离线");
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
        ActiveCaptureTask task = currentTask;
        currentTask = null;
        releaseTickets(task);
        if (backgroundExecutor != null) backgroundExecutor.shutdownNow();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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
