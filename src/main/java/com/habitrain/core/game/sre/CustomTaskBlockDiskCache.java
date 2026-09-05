package com.habitrain.core.game.sre;

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
import java.util.*;

/**
 * 磁盘持久化缓存：将各地图扫描出的自定义任务方块保存为 JSON，
 * 避免开局重复扫描世界方块，并防止因列车区块尚未加载导致扫出 0 条目。
 */
public final class CustomTaskBlockDiskCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomTaskBlockDiskCache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIR_NAME = "map_custom_task_block_caches";

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
        return cacheDir.resolve(mapName + ".cache.json").toFile();
    }

    public static boolean save(ServerLevel level, String mapName, Map<BlockPos, Set<Integer>> snapshot) {
        if (level == null || mapName == null || mapName.isBlank() || snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        try {
            File file = getCacheFile(level, mapName);
            if (file == null) {
                return false;
            }
            File parent = file.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }

            CacheFileModel model = new CacheFileModel(mapName, snapshot);
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(model, writer);
            }
            LOGGER.info("[CustomTaskBlockDiskCache] 已保存地图 {} 的自定义任务方块缓存至磁盘: {} 条目 ({})",
                    mapName, model.entries.size(), file.getName());
            return true;
        } catch (Exception e) {
            LOGGER.error("[CustomTaskBlockDiskCache] 保存地图 {} 自定义方块缓存失败", mapName, e);
            return false;
        }
    }

    public static Map<BlockPos, Set<Integer>> load(ServerLevel level, String mapName) {
        if (level == null || mapName == null || mapName.isBlank()) {
            return Collections.emptyMap();
        }
        File file = getCacheFile(level, mapName);
        if (file == null || !file.exists() || !file.isFile()) {
            return Collections.emptyMap();
        }
        try (FileReader reader = new FileReader(file)) {
            CacheFileModel model = GSON.fromJson(reader, CacheFileModel.class);
            if (model == null || model.entries == null || model.entries.isEmpty()) {
                LOGGER.warn("[CustomTaskBlockDiskCache] 磁盘缓存文件为空或格式损坏: {}", file.getAbsolutePath());
                return Collections.emptyMap();
            }
            Map<BlockPos, Set<Integer>> result = model.toMap();
            LOGGER.info("[CustomTaskBlockDiskCache] 从磁盘加载地图 {} 的自定义方块缓存: {} 条目",
                    mapName, result.size());
            return result;
        } catch (Exception e) {
            LOGGER.error("[CustomTaskBlockDiskCache] 读取地图 {} 磁盘缓存失败: {}", mapName, file.getAbsolutePath(), e);
            return Collections.emptyMap();
        }
    }
}
