package com.habitrain.core.game.sre.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 磁盘持久化缓存：将各地图扫描出的自定义任务方块保存为 JSON，
 * 完整扫描结果（包括空结果）带上下文保存；旧格式和不匹配的加载版本拒绝复用。
 */
public final class CustomTaskBlockDiskCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomTaskBlockDiskCache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIR_NAME = "map_custom_task_block_caches";
    private static final int SCHEMA_VERSION = 2;

    public static class Entry {
        public int x;
        public int y;
        public int z;
        public ArrayList<Integer> types;

        public Entry() {
            this.types = new ArrayList<>();
        }

        public Entry(BlockPos pos, Set<Integer> typeIds) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.types = new ArrayList<>(typeIds);
        }
    }

    public static class CacheFileModel {
        public String mapName;
        public int schemaVersion;
        public CustomTaskBlockScanKey context;
        public String status;
        public ArrayList<Entry> entries;

        public CacheFileModel() {
            this.entries = new ArrayList<>();
        }

        public CacheFileModel(String mapName, Map<BlockPos, Set<Integer>> snapshot) {
            this.mapName = mapName;
            this.entries = new ArrayList<>();
            if (snapshot != null) {
                for (var entry : snapshot.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        this.entries.add(new Entry(entry.getKey(), entry.getValue()));
                    }
                }
            }
        }

        CacheFileModel(CustomTaskBlockScanKey context, Map<BlockPos, Set<Integer>> snapshot) {
            this(context.mapName(), snapshot);
            this.schemaVersion = SCHEMA_VERSION;
            this.context = context;
            this.status = "COMPLETE";
        }

        Optional<Map<BlockPos, Set<Integer>>> validated(CustomTaskBlockScanKey expected) {
            if (schemaVersion != SCHEMA_VERSION || !"COMPLETE".equals(status)
                    || expected == null || !expected.equals(context) || entries == null) return Optional.empty();
            Map<BlockPos, Set<Integer>> result = new HashMap<>();
            for (Entry entry : entries) {
                if (entry == null || entry.types == null || entry.types.isEmpty()
                        || entry.x < expected.minX() || entry.x > expected.maxX()
                        || entry.y < expected.minY() || entry.y > expected.maxY()
                        || entry.z < expected.minZ() || entry.z > expected.maxZ()
                        || entry.types.stream().anyMatch(t -> t == null || t < CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID))
                    return Optional.empty();
                result.put(new BlockPos(entry.x, entry.y, entry.z), new HashSet<>(entry.types));
            }
            return Optional.of(result);
        }

        public Map<BlockPos, Set<Integer>> toMap() {
            Map<BlockPos, Set<Integer>> result = new HashMap<>();
            if (entries != null) {
                for (Entry entry : entries) {
                    if (entry.types != null && !entry.types.isEmpty()) {
                        result.put(new BlockPos(entry.x, entry.y, entry.z), new HashSet<>(entry.types));
                    }
                }
            }
            return result;
        }
    }

    private CustomTaskBlockDiskCache() {
    }

    public static File getCacheFile(ServerLevel level, String mapName) {
        if (level == null || mapName == null || mapName.isBlank()) {
            return null;
        }
        return getCacheFile(level.getServer(), mapName);
    }

    public static File getCacheFile(MinecraftServer server, String mapName) {
        if (server == null || mapName == null || mapName.isBlank()) {
            return null;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path cacheDir = root.resolve(CACHE_DIR_NAME);
        Path result = cacheDir.resolve(mapName + ".cache.json").normalize();
        return result.startsWith(cacheDir.normalize()) ? result.toFile() : null;
    }

    public static boolean save(ServerLevel level, String mapName, Map<BlockPos, Set<Integer>> snapshot) {
        CustomTaskBlockScanKey context = CustomTaskBlockScanner.currentContext(level);
        return context != null && context.mapName().equals(mapName) && save(level, context, snapshot);
    }

    static boolean save(ServerLevel level, CustomTaskBlockScanKey context, Map<BlockPos, Set<Integer>> snapshot) {
        if (level == null || context == null || context.mapName().isBlank() || snapshot == null) {
            return false;
        }
        String mapName = context.mapName();
        try {
            File file = getCacheFile(level, mapName);
            if (file == null) {
                return false;
            }
            File parent = file.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }

            CacheFileModel model = new CacheFileModel(context, snapshot);
            if (model.validated(context).isEmpty()) return false;
            Path temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(model, writer);
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[CustomTaskBlockDiskCache] 已保存地图 {} 的自定义任务方块缓存至磁盘: {} 条目 ({})",
                    mapName, model.entries.size(), file.getName());
            return true;
        } catch (Exception e) {
            LOGGER.error("[CustomTaskBlockDiskCache] 保存地图 {} 自定义方块缓存失败", mapName, e);
            return false;
        }
    }

    public static Map<BlockPos, Set<Integer>> load(ServerLevel level, String mapName) {
        CustomTaskBlockScanKey context = CustomTaskBlockScanner.currentContext(level);
        if (context == null || !context.mapName().equals(mapName)) return Map.of();
        return loadValidated(level, context).orElse(Map.of());
    }

    static Optional<Map<BlockPos, Set<Integer>>> loadValidated(ServerLevel level, CustomTaskBlockScanKey context) {
        if (level == null || context == null || context.mapName().isBlank()) return Optional.empty();
        String mapName = context.mapName();
        File file = getCacheFile(level, mapName);
        if (file == null || !file.exists() || !file.isFile()) {
            return Optional.empty();
        }
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            CacheFileModel model = GSON.fromJson(reader, CacheFileModel.class);
            return model == null ? Optional.empty() : model.validated(context);
        } catch (Exception e) {
            LOGGER.error("[CustomTaskBlockDiskCache] 读取地图 {} 磁盘缓存失败: {}", mapName, file.getAbsolutePath(), e);
            return Optional.empty();
        }
    }
}
