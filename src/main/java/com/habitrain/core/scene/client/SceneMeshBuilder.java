package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.model.SceneBounds;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private static final int SECTIONS_PER_STEP = 2;
    private static final long STEP_BUDGET_NANOS = 4_000_000L;
    /** 约 2,097,152 quads * 128 bytes/quad = 256 MiB。 */
    private static final long MAX_QUADS = 2_097_152L;

    private SceneMeshBuilder() {}

    public static CompletableFuture<SceneMeshSet> buildMeshAsync(SceneAssetCodec.AssetData assetData) {
        if (assetData == null || assetData.sections.isEmpty()) {
            return CompletableFuture.completedFuture(new SceneMeshSet());
        }
        if (assetData.sections.size() > MAX_SECTIONS) {
            LOGGER.warn("拒绝构建超限场景网格: sections={}", assetData.sections.size());
            return CompletableFuture.completedFuture(new SceneMeshSet());
        }

        CompletableFuture<SceneMeshSet> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(new BuildState(assetData, result)::advance);
        return result;
    }

    private static final class BuildState {
        private final SceneAssetCodec.AssetData asset;
        private final CompletableFuture<SceneMeshSet> result;
        private final Map<SceneMeshSet.Layer, ByteBufferBuilder> byteBuilders =
                new EnumMap<>(SceneMeshSet.Layer.class);
        private final Map<SceneMeshSet.Layer, BufferBuilder> builders =
                new EnumMap<>(SceneMeshSet.Layer.class);
        private final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        private final RandomSource random = RandomSource.create(42L);
        private final PoseStack poses = new PoseStack();
        private final SnapshotBlockView blockView;
        private int sectionIndex;
        private long quadCount;

        private BuildState(SceneAssetCodec.AssetData asset, CompletableFuture<SceneMeshSet> result) {
            this.asset = asset;
            this.result = result;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                throw new IllegalStateException("Cannot build a scene mesh without a client level");
            }
            this.blockView = new SnapshotBlockView(asset, level);
            for (SceneMeshSet.Layer layer : SceneMeshSet.Layer.values()) {
                ByteBufferBuilder bytes = new ByteBufferBuilder(131072);
                byteBuilders.put(layer, bytes);
                builders.put(layer, new BufferBuilder(bytes,
                        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK));
            }
        }

        private void advance() {
            if (result.isDone()) return;
            try {
                long start = System.nanoTime();
                int processed = 0;
                while (sectionIndex < asset.sections.size()
                        && processed < SECTIONS_PER_STEP
                        && System.nanoTime() - start < STEP_BUDGET_NANOS) {
                    processSection(asset.sections.get(sectionIndex++));
                    processed++;
                }
                if (sectionIndex < asset.sections.size()) {
                    CompletableFuture.delayedExecutor(1L, TimeUnit.MILLISECONDS)
                            .execute(() -> Minecraft.getInstance().execute(this::advance));
                    return;
                }
                result.complete(upload());
            } catch (Throwable t) {
                LOGGER.error("构建场景顶点网格失败", t);
                result.complete(new SceneMeshSet());
            }
        }

        private void processSection(SceneAssetCodec.SectionData section) {
            SnapshotSection snapshotSection = blockView.getSection(section.relX, section.relY, section.relZ);
            if (snapshotSection == null) return;
            BlockState[] palette = snapshotSection.palette;

            int baseX = localSectionOrigin(section.relX, asset.sourceBounds.minX());
            int baseY = localSectionOrigin(section.relY, asset.sourceBounds.minY());
            int baseZ = localSectionOrigin(section.relZ, asset.sourceBounds.minZ());

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int flat = (y << 8) | (z << 4) | x;
                        int paletteIndex = flat < section.blockIndices.length ? section.blockIndices[flat] : 0;
                        if (paletteIndex <= 0 || paletteIndex >= palette.length) continue;
                        BlockState state = palette[paletteIndex];
                        if (state == null || state.isAir()) continue;

                        int localX = baseX + x;
                        int localY = baseY + y;
                        int localZ = baseZ + z;
                        BlockPos localPos = new BlockPos(localX, localY, localZ);
                        BakedModel model = dispatcher.getBlockModel(state);
                        BufferBuilder builder = builders.get(resolveLayer(state));

                        countModelQuads(state, model);
                        poses.pushPose();
                        poses.translate(localX, localY, localZ);
                        dispatcher.renderBatched(state, localPos, blockView, poses, builder, true, random);
                        poses.popPose();

                        FluidState fluidState = state.getFluidState();
                        if (!fluidState.isEmpty()) {
                            countQuads(6L);
                            BufferBuilder fluidBuilder = builders.get(resolveLayer(fluidState));
                            dispatcher.renderLiquid(localPos, blockView, fluidBuilder, state, fluidState);
                        }
                    }
                }
            }
        }

        private void countModelQuads(BlockState state, BakedModel model) {
            RandomSource estimateRandom = RandomSource.create(42L);
            for (Direction direction : DIRECTIONS) {
                List<?> quads = model.getQuads(state, direction, estimateRandom);
                countQuads(quads != null ? quads.size() : 0L);
            }
            List<?> unculled = model.getQuads(state, null, estimateRandom);
            countQuads(unculled != null ? unculled.size() : 0L);
        }

        private void countQuads(long count) {
            quadCount += count;
            if (quadCount > MAX_QUADS) {
                throw new IllegalStateException("Scene VBO estimate exceeds 256 MiB");
            }
        }

        private SceneMeshSet upload() {
            SceneMeshSet meshSet = new SceneMeshSet();
            for (Map.Entry<SceneMeshSet.Layer, BufferBuilder> entry : builders.entrySet()) {
                try {
                    MeshData data = entry.getValue().build();
                    if (data == null) continue;
                    VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffer.bind();
                    buffer.upload(data);
                    VertexBuffer.unbind();
                    meshSet.setBuffer(entry.getKey(), buffer);
                } catch (Throwable noData) {
                    LOGGER.debug("场景图层 {} 无可上传顶点", entry.getKey());
                }
            }
            return meshSet;
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
     */
    private static final class SnapshotBlockView implements BlockAndTintGetter {
        private final SceneBounds bounds;
        private final ClientLevel level;
        private final Map<SectionKey, SnapshotSection> sections = new HashMap<>();

        private SnapshotBlockView(SceneAssetCodec.AssetData asset, ClientLevel level) {
            this.bounds = asset.sourceBounds;
            this.level = level;
            for (SceneAssetCodec.SectionData section : asset.sections) {
                BlockState[] palette = new BlockState[section.palette.size()];
                for (int i = 0; i < section.palette.size(); i++) {
                    palette[i] = parseBlockState(section.palette.get(i));
                }
                sections.put(new SectionKey(section.relX, section.relY, section.relZ),
                        new SnapshotSection(palette, section.blockIndices, section.skyLight, section.blockLight));
            }
        }

        private SnapshotSection getSection(int relX, int relY, int relZ) {
            return sections.get(new SectionKey(relX, relY, relZ));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
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
            if (!bounds.contains(sourceX, sourceY, sourceZ)) return null;

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
