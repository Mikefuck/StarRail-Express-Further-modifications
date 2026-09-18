package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.scene.compat.SceneBakeContext;
import com.habitrain.core.api.client.scene.compat.SceneBakeResult;
import com.habitrain.core.api.client.scene.compat.SceneBlockMeshAdapter;
import com.habitrain.core.api.client.scene.compat.SceneMaterialSink;
import com.habitrain.core.api.scene.compat.SceneBlockPayloadEntry;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.client.compat.SceneBlockMeshAdapterRegistry;
import com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景网格构建器。
 *
 * Minecraft 模型与 GPU 对象只在客户端渲染执行器访问；每次最多推进 2 个 section
 * 或 4 ms，并在下一次客户端任务中继续，避免后台线程读取 BakedModel。
 */
public final class SceneMeshBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneMeshBuilder.class.getSimpleName());
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int MAX_SECTIONS = SceneLimits.MAX_MESH_SECTIONS;
    /** 单个 quad 的顶点数据估算（4 个顶点 × 32 字节的 BLOCK 顶点格式）。 */
    /** 约 2,097,152 quads * 128 bytes/quad = 256 MiB。 */
    private static final long MAX_QUADS = 2_097_152L;
    private static final AtomicBoolean FABRIC_CONTEXT_FALLBACK_WARNED = new AtomicBoolean();
    /**
     * 运行/预取路径默认只做统计与异常抽样；发布诊断与管理员检查会显式要求详细模式。
     * 逐条保留正常方块会让大型场景的报告本身成为主要内存占用。
     */
    private static final boolean DEFAULT_DETAILED_DIAGNOSTICS =
            com.habitrain.core.client.config.SceneClientPerformanceRules.REPORT_DETAILED_DIAGNOSTICS;

    private SceneMeshBuilder() {}

    public record MeshBuildResult(SceneMeshSet meshSet, SceneCompatibilityReport report) {}

    public static CompletableFuture<MeshBuildResult> buildMeshWithReportAsync(SceneAssetCodec.AssetData assetData) {
        return buildMeshWithReportAsync(assetData, DEFAULT_DETAILED_DIAGNOSTICS,
                SceneBuildScheduler.Priority.PREFETCH);
    }

    public static CompletableFuture<MeshBuildResult> buildMeshWithReportAsync(SceneAssetCodec.AssetData assetData,
                                                                             boolean detailedDiagnostics) {
        return buildMeshWithReportAsync(assetData, detailedDiagnostics,
                SceneBuildScheduler.Priority.PREFETCH);
    }

    /**
     * 提交一次网格构建。
     *
     * <p>构建不再自带预算、也不再自行重排（此前每个 BuildState 用 {@code delayedExecutor}
     * 私有地推进 4 ms）；它被交给 {@link SceneBuildScheduler}，与所有其他构建共享一份帧级总预算。</p>
     *
     * @param detailedDiagnostics true 时逐条保留正常方块条目（发布/管理员诊断用），
     *                            false 时正常条目按位置聚合成计数，只保留异常抽样
     * @param priority            与下载队列同源的优先级：主背景/预览优先于附加背景，附加背景优先于预取
     */
    public static CompletableFuture<MeshBuildResult> buildMeshWithReportAsync(SceneAssetCodec.AssetData assetData,
                                                                             boolean detailedDiagnostics,
                                                                             SceneBuildScheduler.Priority priority) {
        if (assetData == null || assetData.sections.isEmpty()) {
            SceneCompatibilityReport empty = new SceneCompatibilityReport(detailedDiagnostics);
            empty.freeze();
            return CompletableFuture.completedFuture(new MeshBuildResult(new SceneMeshSet(), empty));
        }
        if (assetData.sections.size() > MAX_SECTIONS) {
            LOGGER.warn("拒绝构建超限场景网格: sections={}", assetData.sections.size());
            SceneCompatibilityReport empty = new SceneCompatibilityReport(detailedDiagnostics);
            empty.freeze();
            return CompletableFuture.completedFuture(new MeshBuildResult(new SceneMeshSet(), empty));
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("Cannot build a scene mesh without a client level");
        }

        // BuildState 的构造已经很轻（调色板改为惰性解析），因此仍同步建好再交给调度器。
        CompletableFuture<MeshBuildResult> result = new CompletableFuture<>();
        SceneBuildScheduler.getInstance().submit(
                new BuildState(assetData, result, detailedDiagnostics), priority);
        return result;
    }

    public static CompletableFuture<SceneMeshSet> buildMeshAsync(SceneAssetCodec.AssetData assetData) {
        return buildMeshWithReportAsync(assetData).thenApply(MeshBuildResult::meshSet);
    }

    private static final class BuildState implements SceneBuildScheduler.BuildStep {
        private enum FabricBakeOutcome {
            EMITTED,
            FALLBACK_TO_ACTIVE_RENDERER,
            TERMINAL
        }

        private final SceneAssetCodec.AssetData asset;
        private final CompletableFuture<MeshBuildResult> result;
        private final SceneCompatibilityReport report;
        private final SceneMeshBatchPolicy.Plan batchPlan;
        /**
         * 分批后的构建器：外层键是层/材质，数组下标是批次。
         *
         * <p>不透明层按空间批次分组（裁剪粒度就是它）；{@code TRANSLUCENT} 与半透明自定义材质
         * 固定用下标 0——半透明不拆批，保住原来的发射顺序。构建器按需创建，空批次不占原生内存。</p>
         */
        private final Map<SceneMeshSet.Layer, ByteBufferBuilder[]> byteBuilders =
                new EnumMap<>(SceneMeshSet.Layer.class);
        private final Map<SceneMeshSet.Layer, BufferBuilder[]> builders =
                new EnumMap<>(SceneMeshSet.Layer.class);
        private final Map<SceneMaterialKey, ByteBufferBuilder[]> customByteBuilders = new LinkedHashMap<>();
        private final Map<SceneMaterialKey, BufferBuilder[]> customBuilders = new LinkedHashMap<>();
        /** 当前 section 所属的批次。 */
        private int activeBatch;
        private final Map<BlockPos, SceneBlockPayloadEntry> payloadMap = new HashMap<>();
        private final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        private final RandomSource random = RandomSource.create(42L);
        private final PoseStack poses = new PoseStack();
        private final SnapshotBlockView blockView;
        private final SpriteFinder blockSpriteFinder;
        private int sectionIndex;
        /** 当前 section 的可中断游标：activeLayer 是已完成的 y 层数（0..16）。 */
        private SceneAssetCodec.SectionData activeSection;
        private BlockState[] activePalette;
        private int activeBaseX;
        private int activeBaseY;
        private int activeBaseZ;
        private int activeLayer;
        private long quadCount;
        private boolean cpuBuildersClosed;
        /** section 阶段是否已经跑完；之后进入 upload 阶段。 */
        private boolean sectionsDone;
        /** upload 阶段的游标：按层再来按自定义材质，逐个 build + upload。 */
        private SceneMeshSet uploadTarget;
        /** 上传工作项：一个 (层或材质, 批次) 的构建器。一个 list 顺序推进，便于逐项检查预算。 */
        private record Upload(SceneMeshSet.Layer layer, SceneMaterialKey material, int batch,
                              BufferBuilder builder) {}
        private List<Upload> pendingUploads = List.of();
        private int uploadCursor;
        /** 批次下标 → 该批的网格集合（惰性创建，只有真的产出几何才会有）。 */
        private final Map<Integer, SceneMeshSet> batchTargets = new LinkedHashMap<>();
        private final Map<Integer, SceneVertexBounds> batchBounds = new LinkedHashMap<>();

        private BuildState(SceneAssetCodec.AssetData asset, CompletableFuture<MeshBuildResult> result,
                           boolean detailedDiagnostics) {
            this.asset = asset;
            this.result = result;
            this.report = new SceneCompatibilityReport(detailedDiagnostics);
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                throw new IllegalStateException("Cannot build a scene mesh without a client level");
            }
            this.blockView = new SnapshotBlockView(asset, level);
            this.blockSpriteFinder = SpriteFinder.get(
                    Minecraft.getInstance().getModelManager().getAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS));
            this.batchPlan = SceneMeshBatchPolicy.plan(asset.sections.size(),
                    com.habitrain.core.client.config.ClientVisualPreferences.getMeshBatchMinSections(),
                    asset.sourceBounds);
            if (asset.blockPayloads != null) {
                for (SceneBlockPayloadEntry entry : asset.blockPayloads) {
                    payloadMap.put(new BlockPos(entry.localX(), entry.localY(), entry.localZ()), entry);
                }
            }
        }

        /**
         * 由 {@link SceneBuildScheduler} 驱动的增量推进：先跑完全部 section，再逐个上传缓冲。
         *
         * <p>预算由调度器在所有构建之间共享，因此这里不再持有任何私有配额。</p>
         */
        @Override
        public boolean step(long deadlineNanos) {
            if (result.isDone()) {
                closeCpuBuilders();
                closeUploadedMeshes();
                return true;
            }
            // 构建现在会跨越多帧，可能在关卡已被替换之后才轮到执行；快照视图持有旧 ClientLevel，
            // 继续推进只会在错误的世界上采样光照与遮挡。
            if (Minecraft.getInstance().level != blockView.level) {
                LOGGER.debug("场景网格构建跨越了关卡切换，已放弃: sections={}", asset.sections.size());
                abort();
                return true;
            }
            try {
                if (!sectionsDone) {
                    if (!stepSections(deadlineNanos)) return false;
                    sectionsDone = true;
                    beginUpload();
                }
                return stepUpload(deadlineNanos);
            } catch (Throwable t) {
                closeCpuBuilders();
                closeUploadedMeshes();
                LOGGER.error("构建场景顶点网格失败", t);
                // 失败路径同样要冻结：部分统计信息对诊断仍然有价值。
                report.freeze();
                result.complete(new MeshBuildResult(new SceneMeshSet(), report));
                return true;
            }
        }

        @Override
        public void abort() {
            closeCpuBuilders();
            closeUploadedMeshes();
            if (!result.isDone()) {
                report.freeze();
                result.complete(new MeshBuildResult(new SceneMeshSet(), report));
            }
        }

        private void closeUploadedMeshes() {
            if (uploadTarget != null) {
                uploadTarget.close();
                uploadTarget = null;
            }
            batchTargets.values().forEach(SceneMeshSet::close);
            batchTargets.clear();
            batchBounds.clear();
        }

        /**
         * 取当前 section 所属批次、指定层的构建器（按需创建）。
         *
         * <p>半透明层与半透明自定义材质固定落在批次 0：它们不拆批，所有 section 都追加到同一个
         * 缓冲里，绘制顺序与分批功能上线前逐字节一致。</p>
         */
        private BufferBuilder builderFor(SceneMeshSet.Layer layer) {
            int slot = (batchPlan.isBatched() && layer != SceneMeshSet.Layer.TRANSLUCENT) ? activeBatch : 0;
            BufferBuilder[] array = builders.computeIfAbsent(layer,
                    ignored -> new BufferBuilder[batchPlan.batchCount()]);
            ByteBufferBuilder[] byteArray = byteBuilders.computeIfAbsent(layer,
                    ignored -> new ByteBufferBuilder[batchPlan.batchCount()]);
            BufferBuilder builder = array[slot];
            if (builder == null) {
                ByteBufferBuilder bytes = new ByteBufferBuilder(131072);
                byteArray[slot] = bytes;
                builder = new BufferBuilder(bytes,
                        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                array[slot] = builder;
            }
            return builder;
        }

        private BufferBuilder getOrCreateCustomBuilder(SceneMaterialKey material) {
            int slot = (batchPlan.isBatched() && !material.isTranslucent()) ? activeBatch : 0;
            BufferBuilder[] array = customBuilders.computeIfAbsent(material,
                    ignored -> new BufferBuilder[batchPlan.batchCount()]);
            ByteBufferBuilder[] byteArray = customByteBuilders.computeIfAbsent(material,
                    ignored -> new ByteBufferBuilder[batchPlan.batchCount()]);
            BufferBuilder builder = array[slot];
            if (builder == null) {
                ByteBufferBuilder bytes = new ByteBufferBuilder(65536);
                byteArray[slot] = bytes;
                builder = new BufferBuilder(bytes, material.vertexFormatMode(), DefaultVertexFormat.BLOCK);
                array[slot] = builder;
            }
            return builder;
        }

        private final class MaterialSinkImpl implements SceneMaterialSink {
            private final BlockPos currentLocalPos;
            private int emittedCount = 0;

            MaterialSinkImpl(BlockPos pos) {
                this.currentLocalPos = pos;
            }

            boolean hasEmitted() {
                return emittedCount > 0;
            }

            int emittedCount() {
                return emittedCount;
            }

            @Override
            public void emitQuad(SceneMaterialKey material,
                                  SceneRenderPayload.VisualVertex v0,
                                  SceneRenderPayload.VisualVertex v1,
                                  SceneRenderPayload.VisualVertex v2,
                                  SceneRenderPayload.VisualVertex v3) {
                if (material == null) material = SceneMaterialKey.SOLID;
                BufferBuilder builder = getOrCreateCustomBuilder(material);
                countQuads(1L);
                emittedCount++;
                emitVertex(builder, v0, currentLocalPos, material.emissive());
                emitVertex(builder, v1, currentLocalPos, material.emissive());
                emitVertex(builder, v2, currentLocalPos, material.emissive());
                emitVertex(builder, v3, currentLocalPos, material.emissive());
            }

            @Override
            public void emitTriangle(SceneMaterialKey material,
                                      SceneRenderPayload.VisualVertex v0,
                                      SceneRenderPayload.VisualVertex v1,
                                      SceneRenderPayload.VisualVertex v2) {
                if (material == null) material = SceneMaterialKey.direct(null, SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.PrimitiveMode.TRIANGLES);
                BufferBuilder builder = getOrCreateCustomBuilder(material);
                countQuads(1L);
                emittedCount++;
                emitVertex(builder, v0, currentLocalPos, material.emissive());
                emitVertex(builder, v1, currentLocalPos, material.emissive());
                emitVertex(builder, v2, currentLocalPos, material.emissive());
            }

            @Override
            public void emitQuad(ResourceLocation texture,
                                  SceneRenderPayload.VisualVertex v0,
                                  SceneRenderPayload.VisualVertex v1,
                                  SceneRenderPayload.VisualVertex v2,
                                  SceneRenderPayload.VisualVertex v3) {
                SceneMaterialKey key = SceneMaterialKey.direct(texture, SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.PrimitiveMode.QUADS);
                emitQuad(key, v0, v1, v2, v3);
            }

            @Override
            public void emitTriangle(ResourceLocation texture,
                                      SceneRenderPayload.VisualVertex v0,
                                      SceneRenderPayload.VisualVertex v1,
                                      SceneRenderPayload.VisualVertex v2) {
                SceneMaterialKey key = SceneMaterialKey.direct(texture, SceneMaterialKey.BlendMode.SOLID, SceneMaterialKey.PrimitiveMode.TRIANGLES);
                emitTriangle(key, v0, v1, v2);
            }
        }

        private void emitVertex(BufferBuilder builder, SceneRenderPayload.VisualVertex v,
                                BlockPos localPos, boolean emissive) {
            float x = localPos.getX() + v.x();
            float y = localPos.getY() + v.y();
            float z = localPos.getZ() + v.z();
            int color = v.color() != 0 ? v.color() : 0xFFFFFFFF;
            if ((color >>> 24) == 0) color |= 0xFF000000;
            int light = emissive ? 0x00F000F0
                    : v.light() >= 0 ? v.light() : resolveBlockLight(localPos);
            builder.addVertex(x, y, z)
                    .setColor(color)
                    .setUv(v.u(), v.v())
                    .setLight(light)
                    .setNormal(v.nx(), v.ny(), v.nz());
        }

        private int resolveBlockLight(BlockPos localPos) {
            int sky = blockView.getBrightness(LightLayer.SKY, localPos);
            int block = blockView.getBrightness(LightLayer.BLOCK, localPos);
            return (sky << 20) | (block << 4);
        }

        /**
         * 取下一个可处理的 section 并摆好游标；空 section 直接跳过。
         *
         * @return false 表示所有 section 都已经处理完
         */
        private boolean beginNextSection() {
            while (sectionIndex < asset.sections.size()) {
                SceneAssetCodec.SectionData section = asset.sections.get(sectionIndex);
                SnapshotSection snapshot = blockView.getSection(section.relX, section.relY, section.relZ);
                if (snapshot == null) {
                    sectionIndex++;
                    continue;
                }
                activeSection = section;
                activePalette = snapshot.palette;
                activeBaseX = localSectionOrigin(section.relX, asset.sourceBounds.minX());
                activeBaseY = localSectionOrigin(section.relY, asset.sourceBounds.minY());
                activeBaseZ = localSectionOrigin(section.relZ, asset.sourceBounds.minZ());
                // 批次只在 section 边界上变化：section 内的方块共用同一个包围盒，不需要逐块判断。
                activeBatch = batchPlan.batchIndex(SceneMeshBatchPolicy.axisCoordinate(
                        batchPlan, activeBaseX, activeBaseY, activeBaseZ));
                activeLayer = 0;
                return true;
            }
            return false;
        }

        /**
         * 推进 section 阶段，每完成一个 y 层（256 个方块）检查一次共享预算。
         *
         * <p>切分粒度选 y 层而不是整个 section，是因为此前预算只在 section 之间检查：
         * 单个密集装饰 section 可以一次吃掉远超一帧的时间。这里保留原有的 {@code y → z → x}
         * 遍历顺序不变——顶点追加顺序决定了半透明层的绘制顺序，不能改。</p>
         *
         * @return true 表示全部 section 处理完毕
         */
        private boolean stepSections(long deadlineNanos) {
            if (activeSection == null && !beginNextSection()) return true;

            for (int y = activeLayer; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int flat = sectionFlatIndex(x, y, z);
                        int paletteIndex = flat < activeSection.blockIndices.length
                                ? activeSection.blockIndices[flat] : 0;
                        if (paletteIndex <= 0 || paletteIndex >= activePalette.length) continue;
                        BlockState state = activePalette[paletteIndex];
                        if (state == null || state.isAir()) continue;

                        int localX = activeBaseX + x;
                        int localY = activeBaseY + y;
                        int localZ = activeBaseZ + z;
                        int worldX = asset.sourceBounds.minX() + localX;
                        int worldY = asset.sourceBounds.minY() + localY;
                        int worldZ = asset.sourceBounds.minZ() + localZ;
                        if (!asset.sourceBounds.contains(worldX, worldY, worldZ)) continue;

                        BlockPos localPos = new BlockPos(localX, localY, localZ);
                        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        String modelClassName = null;
                        try {
                            BakedModel m = dispatcher.getBlockModel(state);
                            if (m != null) modelClassName = m.getClass().getSimpleName();
                        } catch (Throwable ignored) {}

                        SceneBlockPayloadEntry entry = payloadMap.get(localPos);
                        SceneRenderPayload payload = entry != null ? entry.payload() : null;
                        boolean legacyStaticModelHint = entry != null
                                && BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID.equals(entry.adapterId());

                        // 1. 资产携带视觉载荷时必须按写入的 adapterId + version 精确匹配。
                        //    不能让另一个“碰巧 supports”的适配器接管未知格式。
                        SceneBlockMeshAdapterRegistry adapterRegistry = SceneBlockMeshAdapterRegistry.getInstance();
                        SceneBlockMeshAdapter adapter = null;
                        if (entry != null && !legacyStaticModelHint) {
                            adapter = adapterRegistry.get(entry.adapterId());
                            if (adapter == null) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, entry.adapterId(),
                                        entry.adapterVersion(), state.hasBlockEntity(), true,
                                        SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                                        "客户端缺少资产指定的网格适配器", 0, null, 0, 15
                                ));
                                continue;
                            }
                            if (adapter.dataVersion() != entry.adapterVersion()) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, entry.adapterId(),
                                        entry.adapterVersion(), state.hasBlockEntity(), true,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "适配器版本不匹配：资产=" + entry.adapterVersion()
                                                + "，客户端=" + adapter.dataVersion(),
                                        0, null, 0, 15
                                ));
                                continue;
                            }
                            final boolean supported;
                            try {
                                supported = adapter.supports(state, payload);
                            } catch (Throwable t) {
                                LOGGER.error("适配器 {} 探测方块 {} 异常", adapter.adapterId(), blockId, t);
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry.adapterVersion(), state.hasBlockEntity(), true,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "适配器探测异常", 0, null, 0, 15
                                ));
                                continue;
                            }
                            if (!supported) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry.adapterVersion(), state.hasBlockEntity(), true,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "资产指定的适配器不支持该方块或载荷", 0, null, 0, 15
                                ));
                                continue;
                            }
                        } else if (!legacyStaticModelHint) {
                            try {
                                adapter = adapterRegistry.findAdapter(state, null);
                            } catch (Throwable t) {
                                LOGGER.error("客户端适配器探测方块 {} 异常", blockId, t);
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, null, 0,
                                        state.hasBlockEntity(), false,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "客户端适配器探测异常", 0, null, 0, 15
                                ));
                                continue;
                            }
                        }
                        if (adapter != null) {
                            MaterialSinkImpl sink = new MaterialSinkImpl(localPos);
                            SceneBakeContext ctx = new SceneBakeContext(localPos, state, payload, blockView);
                            SceneBakeResult bakeResult;
                            try {
                                bakeResult = adapter.emitStaticMesh(ctx, sink);
                            } catch (Throwable t) {
                                LOGGER.error("适配器 {} 烘焙方块 {} 异常", adapter.adapterId(), blockId, t);
                                bakeResult = SceneBakeResult.UNSUPPORTED;
                            }

                            if (bakeResult == SceneBakeResult.SUCCESS && sink.hasEmitted()) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry != null ? entry.adapterVersion() : adapter.dataVersion(),
                                        state.hasBlockEntity(), payload != null,
                                        SceneCompatibilityReport.IssueType.NONE,
                                        "通过专用适配器烘焙成功", sink.emittedCount(), null, 0, 15
                                ));
                                continue;
                            } else if (bakeResult == SceneBakeResult.SUCCESS) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry != null ? entry.adapterVersion() : adapter.dataVersion(),
                                        state.hasBlockEntity(), payload != null,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "适配器报告成功但没有发射任何顶点", 0, null, 0, 15
                                ));
                                continue;
                            } else if (bakeResult == SceneBakeResult.MISSING_TEXTURE) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry != null ? entry.adapterVersion() : adapter.dataVersion(),
                                        state.hasBlockEntity(), payload != null,
                                        SceneCompatibilityReport.IssueType.MISSING_TEXTURE,
                                        "适配器报告缺失纹理资源", 0, null, 0, 15
                                ));
                                continue;
                            } else if (bakeResult == SceneBakeResult.SKIPPED) {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry != null ? entry.adapterVersion() : adapter.dataVersion(),
                                        state.hasBlockEntity(), payload != null,
                                        SceneCompatibilityReport.IssueType.SKIPPED,
                                        "适配器主动跳过该方块", 0, null, 0, 15
                                ));
                                continue;
                            } else {
                                report.addEntry(new SceneCompatibilityReport.Entry(
                                        localPos, blockId, modelClassName, adapter.adapterId(),
                                        entry != null ? entry.adapterVersion() : adapter.dataVersion(),
                                        state.hasBlockEntity(), payload != null,
                                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                                        "适配器不支持该方块或载荷", 0, null, 0, 15
                                ));
                                continue;
                            }
                        }

                        // 2. 检查光照异常
                        int sky = blockView.getBrightness(LightLayer.SKY, localPos);
                        int block = blockView.getBrightness(LightLayer.BLOCK, localPos);
                        if (sky < 0 || sky > 15 || block < 0 || block > 15) {
                            report.addEntry(new SceneCompatibilityReport.Entry(
                                    localPos, blockId, modelClassName, null, 0,
                                    state.hasBlockEntity(), false,
                                    SceneCompatibilityReport.IssueType.ABNORMAL_PACKED_LIGHT,
                                    "快照光照数值异常: sky=" + sky + ", block=" + block, 0, null, sky, block
                            ));
                        }

                        // 3. 检查缺失模型
                        BakedModel model = dispatcher.getBlockModel(state);
                        if (model == dispatcher.getBlockModelShaper().getModelManager().getMissingModel()) {
                            report.addEntry(new SceneCompatibilityReport.Entry(
                                    localPos, blockId, modelClassName, null, 0,
                                    state.hasBlockEntity(), false,
                                    SceneCompatibilityReport.IssueType.MISSING_TEXTURE,
                                    "模型管理器缺失该方块模型", 0, null, 0, 15
                            ));
                        }

                        // 4. Fabric Renderer API 增强模型优先从它实际发出的 quad/material 烘焙。
                        //    对这种模型调用一次 vanilla renderBatched 会把所有 pass 压进预选层。
                        if (SceneFabricModelCollector.isEnhanced(model)) {
                            FabricBakeOutcome outcome = bakeFabricModel(
                                    model, state, localPos, blockId, modelClassName, sky, block);
                            if (outcome == FabricBakeOutcome.EMITTED) {
                                reportBlockEntityStaticFallback(
                                        state, localPos, blockId, modelClassName, true, sky, block);
                                continue;
                            }
                            if (outcome == FabricBakeOutcome.TERMINAL) continue;
                        }

                        // 5. 原版静态模型快速路径，同时也是 Sodium/Indium 增强模型的
                        //    兼容回退。让活动渲染器创建自己的上下文，避免手工 getQuads
                        //    丢掉 Wathe 等模型的面、材质层和嵌套模型。
                        BufferBuilder builder = builderFor(resolveLayer(state));
                        long modelQuadCount = countModelQuads(state, model);
                        poses.pushPose();
                        poses.translate(localX, localY, localZ);
                        dispatcher.renderBatched(state, localPos, blockView, poses, builder, true, random);
                        poses.popPose();

                        FluidState fluidState = state.getFluidState();
                        if (!fluidState.isEmpty()) {
                            countQuads(6L);
                            BufferBuilder fluidBuilder = builderFor(resolveLayer(fluidState));
                            dispatcher.renderLiquid(localPos, blockView, fluidBuilder, state, fluidState);
                        }

                        reportBlockEntityStaticFallback(
                                state, localPos, blockId, modelClassName,
                                hasStaticModelFallback(state, model, modelQuadCount), sky, block);

                        report.addEntry(new SceneCompatibilityReport.Entry(
                                localPos, blockId, modelClassName, null, 0,
                                state.hasBlockEntity(), false,
                                SceneCompatibilityReport.IssueType.NONE,
                                legacyStaticModelHint
                                        ? "旧版静态模型标记已通过活动渲染器兼容烘焙"
                                        : "原版静态模型快速路径",
                                0, SceneMaterialKey.fromLayer(resolveLayer(state)), sky, block
                        ));
                    }
                }
                activeLayer = y + 1;
                // 预算检查点：一帧最多推进一个 y 层的量级，单个重 section 再也无法越过预算。
                if (System.nanoTime() >= deadlineNanos) return false;
            }
            sectionIndex++;
            activeSection = null;
            activePalette = null;
            activeLayer = 0;
            return false;
        }

        private FabricBakeOutcome bakeFabricModel(BakedModel model,
                                                  BlockState state,
                                                  BlockPos localPos,
                                                  ResourceLocation blockId,
                                                  String modelClassName,
                                                  int sky,
                                                  int block) {
            Map<SceneMaterialKey, Integer> verticesByMaterial = new LinkedHashMap<>();
            List<String> materialFailures = new java.util.ArrayList<>();
            long seed = state.getSeed(localPos);
            try {
                SceneFabricModelCollector.CollectionResult collection = SceneFabricModelCollector.collect(
                        model,
                        blockView,
                        state,
                        localPos,
                        () -> RandomSource.create(seed),
                        face -> !Block.shouldRenderFace(
                                state, blockView, localPos, face, localPos.relative(face)),
                        quad -> emitFabricQuad(quad, state, localPos, verticesByMaterial, materialFailures)
                );
                if (collection.rendererContextMismatch()) {
                    if (FABRIC_CONTEXT_FALLBACK_WARNED.compareAndSet(false, true)) {
                        LOGGER.warn("Sodium/Indium FRAPI 拒绝场景收集器上下文；"
                                + "已改用活动渲染器的 renderBatched 完整模型回退");
                    }
                    return FabricBakeOutcome.FALLBACK_TO_ACTIVE_RENDERER;
                }

                int emittedQuads = collection.quadCount();
                if (emittedQuads <= 0 || verticesByMaterial.isEmpty()) {
                    if (state.getRenderShape() == RenderShape.INVISIBLE) {
                        boolean missingBlockEntityAdapter = state.hasBlockEntity()
                                && !blockId.getNamespace().equals("minecraft");
                        report.addEntry(new SceneCompatibilityReport.Entry(
                                localPos, blockId, modelClassName, null, 0,
                                state.hasBlockEntity(), false,
                                missingBlockEntityAdapter
                                        ? SceneCompatibilityReport.IssueType.MISSING_ADAPTER
                                        : SceneCompatibilityReport.IssueType.SKIPPED,
                                missingBlockEntityAdapter
                                        ? "方块仅由第三方方块实体渲染器绘制，缺少专用场景适配器"
                                        : "不可见方块没有可烘焙 quad，已安全跳过",
                                0, null, sky, block
                        ));
                        return FabricBakeOutcome.TERMINAL;
                    }
                    report.addEntry(new SceneCompatibilityReport.Entry(
                            localPos, blockId, modelClassName, null, 0,
                            state.hasBlockEntity(), false,
                            SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                            "Fabric 增强模型没有发射任何可烘焙 quad", 0, null, sky, block
                    ));
                    return FabricBakeOutcome.TERMINAL;
                }

                for (Map.Entry<SceneMaterialKey, Integer> material : verticesByMaterial.entrySet()) {
                    report.addEntry(new SceneCompatibilityReport.Entry(
                            localPos, blockId, modelClassName, null, 0,
                            state.hasBlockEntity(), false,
                            SceneCompatibilityReport.IssueType.NONE,
                            "Fabric Renderer API 实际材质发射路径",
                            material.getValue(),
                            material.getKey(), sky, block
                    ));
                }
                for (String failure : materialFailures) {
                    SceneCompatibilityReport.IssueType type = failure.startsWith("missing_texture:")
                            ? SceneCompatibilityReport.IssueType.MISSING_TEXTURE
                            : SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH;
                    report.addEntry(new SceneCompatibilityReport.Entry(
                            localPos, blockId, modelClassName, null, 0,
                            state.hasBlockEntity(), false, type,
                            failure.substring(failure.indexOf(':') + 1), 0, null, sky, block
                    ));
                }
                return FabricBakeOutcome.EMITTED;
            } catch (Throwable failure) {
                LOGGER.error("Fabric 增强模型 {} 烘焙方块 {} 异常", modelClassName, blockId, failure);
                report.addEntry(new SceneCompatibilityReport.Entry(
                        localPos, blockId, modelClassName, null, 0,
                        state.hasBlockEntity(), false,
                        SceneCompatibilityReport.IssueType.UNSUPPORTED_RENDER_PATH,
                        "Fabric Renderer API 模型发射失败: " + failure.getClass().getSimpleName(),
                        0, null, sky, block
                ));
                return FabricBakeOutcome.TERMINAL;
            }
        }

        private void reportBlockEntityStaticFallback(BlockState state,
                                                     BlockPos localPos,
                                                     ResourceLocation blockId,
                                                     String modelClassName,
                                                     boolean hasStaticMesh,
                                                     int sky,
                                                     int block) {
            if (!state.hasBlockEntity() || blockId.getNamespace().equals("minecraft")) return;

            report.addEntry(new SceneCompatibilityReport.Entry(
                    localPos, blockId, modelClassName, null, 0,
                    true, false,
                    hasStaticMesh
                            ? SceneCompatibilityReport.IssueType.SKIPPED
                            : SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                    hasStaticMesh
                            ? "已保留静态方块模型；方块实体渲染器的动态附加层未写入场景 VBO"
                            : "第三方方块实体没有可用静态模型，缺少专用场景适配器",
                    0, null, sky, block
            ));
        }

        private void emitFabricQuad(QuadView quad,
                                    BlockState state,
                                    BlockPos localPos,
                                    Map<SceneMaterialKey, Integer> verticesByMaterial,
                                    List<String> materialFailures) {
            if (quad.material().glint() == TriState.TRUE) {
                materialFailures.add("unsupported:Fabric glint 材质不能安全转换为场景材质");
                return;
            }

            TextureAtlasSprite sprite = blockSpriteFinder.find(quad);
            if (sprite == null || MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) {
                materialFailures.add("missing_texture:Fabric 模型发射了缺失纹理 sprite");
            }

            SceneMaterialKey material = fabricMaterialKey(
                    quad.material().blendMode().name(),
                    quad.material().emissive(),
                    resolveLayer(state)
            );
            BufferBuilder builder = getOrCreateCustomBuilder(material);
            countQuads(1L);

            Vector3f faceNormal = quad.faceNormal();
            int snapshotLight = resolveBlockLight(localPos);
            for (int vertex = 0; vertex < 4; vertex++) {
                float nx = quad.hasNormal(vertex) ? quad.normalX(vertex) : faceNormal.x();
                float ny = quad.hasNormal(vertex) ? quad.normalY(vertex) : faceNormal.y();
                float nz = quad.hasNormal(vertex) ? quad.normalZ(vertex) : faceNormal.z();
                int color = fabricVertexColor(quad, vertex, state, localPos);
                int light = material.emissive()
                        ? 0x00F000F0
                        : mergePackedLight(snapshotLight, quad.lightmap(vertex));
                builder.addVertex(
                                localPos.getX() + quad.x(vertex),
                                localPos.getY() + quad.y(vertex),
                                localPos.getZ() + quad.z(vertex))
                        .setColor(color)
                        .setUv(quad.u(vertex), quad.v(vertex))
                        .setLight(light)
                        .setNormal(nx, ny, nz);
            }
            verticesByMaterial.merge(material, 4, Integer::sum);
        }

        private int fabricVertexColor(QuadView quad, int vertex, BlockState state, BlockPos localPos) {
            int color = quad.color(vertex);
            if (quad.colorIndex() >= 0 && !quad.material().disableColorIndex()) {
                int tint = Minecraft.getInstance().getBlockColors()
                        .getColor(state, blockView, localPos, quad.colorIndex());
                color = multiplyArgb(color, tint);
            }
            float shade = quad.material().disableDiffuse()
                    ? 1.0f
                    : blockView.getShade(quad.lightFace(), true);
            return shadeArgb(color, shade);
        }

        private long countModelQuads(BlockState state, BakedModel model) {
            RandomSource estimateRandom = RandomSource.create(42L);
            long count = 0L;
            for (Direction direction : DIRECTIONS) {
                List<?> quads = model.getQuads(state, direction, estimateRandom);
                count += quads != null ? quads.size() : 0L;
            }
            List<?> unculled = model.getQuads(state, null, estimateRandom);
            count += unculled != null ? unculled.size() : 0L;
            countQuads(count);
            return count;
        }

        private boolean hasStaticModelFallback(BlockState state, BakedModel model, long modelQuadCount) {
            return state.getRenderShape() == RenderShape.MODEL
                    && (modelQuadCount > 0L || SceneFabricModelCollector.isEnhanced(model));
        }

        private void countQuads(long count) {
            quadCount += count;
            if (quadCount > MAX_QUADS) {
                throw new IllegalStateException("Scene VBO estimate exceeds 256 MiB");
            }
        }

        private void beginUpload() {
            uploadTarget = new SceneMeshSet();
            List<Upload> uploads = new ArrayList<>();
            for (Map.Entry<SceneMeshSet.Layer, BufferBuilder[]> entry : builders.entrySet()) {
                BufferBuilder[] array = entry.getValue();
                for (int batch = 0; batch < array.length; batch++) {
                    if (array[batch] != null) {
                        uploads.add(new Upload(entry.getKey(), null, batch, array[batch]));
                    }
                }
            }
            for (Map.Entry<SceneMaterialKey, BufferBuilder[]> entry : customBuilders.entrySet()) {
                BufferBuilder[] array = entry.getValue();
                for (int batch = 0; batch < array.length; batch++) {
                    if (array[batch] != null) {
                        uploads.add(new Upload(null, entry.getKey(), batch, array[batch]));
                    }
                }
            }
            pendingUploads = uploads;
            uploadCursor = 0;
        }

        /**
         * 一个构建完成的缓冲该落到哪里：分批时进它自己的批次，半透明与未分批时进外层集合。
         */
        private void storeUpload(Upload upload, VertexBuffer buffer, long bytes) {
            boolean translucent = upload.material() != null
                    ? upload.material().isTranslucent()
                    : upload.layer() == SceneMeshSet.Layer.TRANSLUCENT;
            if (!batchPlan.isBatched() || translucent) {
                uploadTarget.setEstimatedBytes(uploadTarget.estimatedBytes() + bytes);
                if (upload.material() != null) {
                    uploadTarget.setCustomBuffer(upload.material(), buffer);
                } else {
                    uploadTarget.setBuffer(upload.layer(), buffer);
                }
                return;
            }
            SceneMeshSet target = batchTargets.computeIfAbsent(upload.batch(),
                    ignored -> new SceneMeshSet());
            target.setEstimatedBytes(target.estimatedBytes() + bytes);
            if (upload.material() != null) {
                target.setCustomBuffer(upload.material(), buffer);
            } else {
                target.setBuffer(upload.layer(), buffer);
            }
        }

        /** 把每个批次连同它的本地 AABB 交给结果集合；没有几何的批次不产生条目。 */
        private void finishBatches() {
            for (Map.Entry<Integer, SceneMeshSet> entry : batchTargets.entrySet()) {
                uploadTarget.addBatch(batchBounds.get(entry.getKey()).batch(entry.getValue()));
            }
        }

        /**
         * 逐个 build + upload 缓冲，之间检查共享预算。
         *
         * <p>单个 {@code MeshData} 无法再切分，因此<strong>一个缓冲</strong>是这里的最小原子单位；
         * 一个超大材质层仍可能越过 deadline，那是这个粒度下不可避免的过冲，而不是遗漏。</p>
         *
         * @return true 表示全部层与材质都已上传，结果已经交付
         */
        private boolean stepUpload(long deadlineNanos) throws Throwable {
            try {
                while (uploadCursor < pendingUploads.size()) {
                    Upload upload = pendingUploads.get(uploadCursor++);
                    MeshData data = upload.builder().build();
                    if (data != null) {
                        boolean batched = batchPlan.isBatched() && (upload.material() != null
                                ? !upload.material().isTranslucent()
                                : upload.layer() != SceneMeshSet.Layer.TRANSLUCENT);
                        try (data) {
                            if (batched) {
                                batchBounds.computeIfAbsent(upload.batch(), ignored -> new SceneVertexBounds())
                                        .include(data.vertexBuffer(), data.drawState().vertexCount(),
                                                DefaultVertexFormat.BLOCK.getVertexSize());
                            }
                            long bytes = (long) data.drawState().vertexCount()
                                    * DefaultVertexFormat.BLOCK.getVertexSize();
                            storeUpload(upload, uploadBuffer(data), bytes);
                        }
                    }
                    if (System.nanoTime() >= deadlineNanos) return false;
                }
                finishBatches();
                // 构建结束即冻结：此后任何 addEntry 都是遗漏的写入，会被记录而不是静默污染读数。
                report.freeze();
                SceneMeshSet completed = uploadTarget;
                uploadTarget = null;
                batchTargets.clear();
                batchBounds.clear();
                if (!result.complete(new MeshBuildResult(completed, report))) completed.close();
                return true;
            } catch (Throwable uploadFailure) {
                closeUploadedMeshes();
                throw uploadFailure;
            } finally {
                if (result.isDone()) closeCpuBuilders();
            }
        }

        private VertexBuffer uploadBuffer(MeshData data) throws Throwable {
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                buffer.bind();
                try {
                    buffer.upload(data);
                } finally {
                    VertexBuffer.unbind();
                }
                return buffer;
            } catch (Throwable uploadFailure) {
                buffer.close();
                throw uploadFailure;
            }
        }

        private void closeCpuBuilders() {
            if (cpuBuildersClosed) return;
            cpuBuildersClosed = true;
            for (ByteBufferBuilder[] array : byteBuilders.values()) {
                for (ByteBufferBuilder bytes : array) {
                    if (bytes != null) bytes.close();
                }
            }
            byteBuilders.clear();
            for (ByteBufferBuilder[] array : customByteBuilders.values()) {
                for (ByteBufferBuilder bytes : array) {
                    if (bytes != null) bytes.close();
                }
            }
            customByteBuilders.clear();
        }
    }

    static int readNibble(byte[] data, int blockIndex) {
        if (data == null || data.length != 2048 || blockIndex < 0 || blockIndex >= 4096) return 0;
        int packed = data[blockIndex >> 1] & 0xFF;
        return (blockIndex & 1) == 0 ? packed & 0x0F : packed >>> 4 & 0x0F;
    }

    static int localSectionOrigin(int relativeSection, int sourceMinBlock) {
        return relativeSection * 16 + Math.floorDiv(sourceMinBlock, 16) * 16 - sourceMinBlock;
    }

    /**
     * section 内方块在资产索引数组里的平坦下标。
     *
     * <p>遍历顺序固定为 {@code y → z → x}，与构建器最初的实现逐位一致：顶点追加顺序决定了
     * 半透明层的绘制顺序，把 section 拆成按 y 层推进的批次时这一点不能变。</p>
     */
    static int sectionFlatIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    static SceneMaterialKey fabricMaterialKey(String fabricBlendMode,
                                               boolean emissive,
                                               SceneMeshSet.Layer defaultLayer) {
        SceneMaterialKey.BlendMode blendMode = switch (fabricBlendMode != null ? fabricBlendMode : "DEFAULT") {
            case "SOLID" -> SceneMaterialKey.BlendMode.SOLID;
            case "CUTOUT_MIPPED" -> SceneMaterialKey.BlendMode.CUTOUT_MIPPED;
            case "CUTOUT" -> SceneMaterialKey.BlendMode.CUTOUT;
            case "TRANSLUCENT" -> SceneMaterialKey.BlendMode.TRANSLUCENT;
            case "DEFAULT" -> SceneMaterialKey.fromLayer(defaultLayer).blendMode();
            default -> throw new IllegalArgumentException("Unsupported Fabric blend mode: " + fabricBlendMode);
        };
        SceneMaterialKey.ShaderFamily shader = blendMode == SceneMaterialKey.BlendMode.TRANSLUCENT
                ? SceneMaterialKey.ShaderFamily.TRANSLUCENT
                : SceneMaterialKey.ShaderFamily.BLOCK;
        return new SceneMaterialKey(
                net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS,
                SceneMaterialKey.UvSpace.BLOCK_ATLAS,
                blendMode,
                true,
                blendMode != SceneMaterialKey.BlendMode.CUTOUT,
                emissive,
                SceneMaterialKey.PrimitiveMode.QUADS,
                shader
        );
    }

    static int mergePackedLight(int first, int second) {
        int block = Math.max((first >>> 4) & 0xF, (second >>> 4) & 0xF);
        int sky = Math.max((first >>> 20) & 0xF, (second >>> 20) & 0xF);
        return (sky << 20) | (block << 4);
    }

    static int multiplyArgb(int color, int tint) {
        int alpha = (color >>> 24) & 0xFF;
        int red = ((color >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255;
        int green = ((color >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255;
        int blue = (color & 0xFF) * (tint & 0xFF) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    static int shadeArgb(int color, float shade) {
        float clamped = Math.max(0.0f, Math.min(1.0f, shade));
        int alpha = (color >>> 24) & 0xFF;
        int red = Math.round(((color >>> 16) & 0xFF) * clamped);
        int green = Math.round(((color >>> 8) & 0xFF) * clamped);
        int blue = Math.round((color & 0xFF) * clamped);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static SceneMeshSet.Layer resolveLayer(BlockState state) {
        RenderType type = ItemBlockRenderTypes.getChunkRenderType(state);
        if (type == RenderType.translucent()) return SceneMeshSet.Layer.TRANSLUCENT;
        if (type == RenderType.cutout()) return SceneMeshSet.Layer.CUTOUT;
        if (type == RenderType.cutoutMipped()) return SceneMeshSet.Layer.CUTOUT_MIPPED;
        return SceneMeshSet.Layer.SOLID;
    }

    private static SceneMeshSet.Layer resolveLayer(FluidState state) {
        RenderType type = ItemBlockRenderTypes.getRenderLayer(state);
        return type == RenderType.translucent() ? SceneMeshSet.Layer.TRANSLUCENT : SceneMeshSet.Layer.SOLID;
    }

    /**
     * Read-only block view backed by the captured scene. Feeding this view to the vanilla
     * block renderer preserves its ambient occlusion, face shade, tint and neighbor-light
     * sampling instead of flattening every baked quad to a single light value.
     *
     * <p><b>调色板惰性解析</b>：此前构造函数会为全部最多 8192 个 section 一次性解析调色板
     * （注册表查找 + 属性解析，十万量级），是一次完全不受预算约束的客户端线程尖峰。现在
     * 只在某个 section 第一次被访问时解析，成本因此落在受共享预算约束的构建步骤里。</p>
     */
    private static final class SnapshotBlockView implements BlockAndTintGetter {
        private final SceneBounds bounds;
        private final SceneBounds haloBounds;
        private final ClientLevel level;
        private final Map<SectionKey, SceneAssetCodec.SectionData> rawSections = new HashMap<>();
        private final Map<SectionKey, SnapshotSection> sections = new HashMap<>();

        private SnapshotBlockView(SceneAssetCodec.AssetData asset, ClientLevel level) {
            this.bounds = asset.sourceBounds;
            this.haloBounds = asset.haloBounds != null && !asset.haloBounds.isEmpty() ? asset.haloBounds : asset.sourceBounds;
            this.level = level;
            for (SceneAssetCodec.SectionData section : asset.sections) {
                rawSections.put(new SectionKey(section.relX, section.relY, section.relZ), section);
            }
        }

        private SnapshotSection getSection(int relX, int relY, int relZ) {
            return sectionFor(new SectionKey(relX, relY, relZ));
        }

        /** 全部访问都在客户端线程上（构建步骤与活动渲染器），因此普通 HashMap 即可。 */
        private SnapshotSection sectionFor(SectionKey key) {
            SnapshotSection cached = sections.get(key);
            if (cached != null) return cached;
            SceneAssetCodec.SectionData raw = rawSections.get(key);
            if (raw == null) return null;
            BlockState[] palette = new BlockState[raw.palette.size()];
            for (int i = 0; i < raw.palette.size(); i++) {
                palette[i] = parseBlockState(raw.palette.get(i));
            }
            SnapshotSection parsed = new SnapshotSection(palette, raw.blockIndices, raw.skyLight, raw.blockLight);
            sections.put(key, parsed);
            return parsed;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            // The one-block halo exists for light/AO sampling only. Treat blocks outside the
            // selected geometry as air so vanilla and FRAPI face-culling cannot remove a
            // boundary face merely because the source build continues beyond the selection.
            if (!containsLocalGeometry(bounds, pos)) return Blocks.AIR.defaultBlockState();
            BlockLookup lookup = lookup(pos);
            if (lookup == null) return Blocks.AIR.defaultBlockState();
            int paletteIndex = lookup.section.blockIndices[lookup.flatIndex];
            return paletteIndex >= 0 && paletteIndex < lookup.section.palette.length
                    ? lookup.section.palette[paletteIndex]
                    : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return Math.max(1, bounds.sizeY());
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return level.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            BlockLookup lookup = lookup(pos);
            if (lookup == null) return layer == LightLayer.SKY ? 15 : 0;
            byte[] light = layer == LightLayer.SKY
                    ? lookup.section.skyLight
                    : lookup.section.blockLight;
            return readNibble(light, lookup.flatIndex);
        }

        @Override
        public int getRawBrightness(BlockPos pos, int ambientDarkness) {
            int sky = Math.max(0, getBrightness(LightLayer.SKY, pos) - ambientDarkness);
            return Math.max(sky, getBrightness(LightLayer.BLOCK, pos));
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            return getBrightness(LightLayer.SKY, pos) >= 15;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            BlockPos sourcePos = new BlockPos(
                    bounds.minX() + pos.getX(),
                    bounds.minY() + pos.getY(),
                    bounds.minZ() + pos.getZ());
            try {
                return level.getBlockTint(sourcePos, resolver);
            } catch (RuntimeException unloadedSourceChunk) {
                return 0xFFFFFF;
            }
        }

        private BlockLookup lookup(BlockPos localPos) {
            int sourceX = bounds.minX() + localPos.getX();
            int sourceY = bounds.minY() + localPos.getY();
            int sourceZ = bounds.minZ() + localPos.getZ();
            if (!haloBounds.contains(sourceX, sourceY, sourceZ)) return null;

            int relX = SectionPos.blockToSectionCoord(sourceX) - bounds.minSectionX();
            int relY = SectionPos.blockToSectionCoord(sourceY) - bounds.minSectionY();
            int relZ = SectionPos.blockToSectionCoord(sourceZ) - bounds.minSectionZ();
            SnapshotSection section = getSection(relX, relY, relZ);
            if (section == null) return null;

            int x = Math.floorMod(sourceX, 16);
            int y = Math.floorMod(sourceY, 16);
            int z = Math.floorMod(sourceZ, 16);
            return new BlockLookup(section, (y << 8) | (z << 4) | x);
        }
    }

    static boolean containsLocalGeometry(SceneBounds bounds, BlockPos localPos) {
        if (bounds == null || localPos == null || bounds.isEmpty()) return false;
        return localPos.getX() >= 0 && localPos.getX() < bounds.sizeX()
                && localPos.getY() >= 0 && localPos.getY() < bounds.sizeY()
                && localPos.getZ() >= 0 && localPos.getZ() < bounds.sizeZ();
    }

    private record SectionKey(int x, int y, int z) {}

    private record SnapshotSection(BlockState[] palette, short[] blockIndices,
                                   byte[] skyLight, byte[] blockLight) {}

    private record BlockLookup(SnapshotSection section, int flatIndex) {}

    public static BlockState parseBlockState(String value) {
        if (value == null || value.isBlank() || value.equals("minecraft:air")) {
            return Blocks.AIR.defaultBlockState();
        }
        int bracket = value.indexOf('[');
        String blockId = (bracket >= 0 ? value.substring(0, bracket) : value).trim();
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.AIR.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == Blocks.AIR) return Blocks.AIR.defaultBlockState();

        BlockState state = block.defaultBlockState();
        if (bracket >= 0 && value.endsWith("]")) {
            for (String property : value.substring(bracket + 1, value.length() - 1).split(",")) {
                String[] pair = property.split("=", 2);
                if (pair.length == 2) state = setPropertyValue(state, pair[0].trim(), pair[1].trim());
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state,
                                                                          String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            Property<T> typed = (Property<T>) property;
            Optional<T> parsed = typed.getValue(value);
            if (parsed.isPresent()) return state.setValue(typed, parsed.get());
        }
        return state;
    }
}
