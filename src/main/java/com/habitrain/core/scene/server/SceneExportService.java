package com.habitrain.core.scene.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 场景资产导出：把当前正式资产按地图名导出成一份可直接塞进整合包的目录。
 *
 * <p>这是"可选外部分发"里唯一不需要额外基建的一条：资产随整合包分发到玩家本地，
 * 客户端启动后由预置目录直接入库（{@code habitrain_scene_seed/}），游戏服务器一个字节都不用出。
 * 报告里说过这不等于"用户总下载量归零"——字节只是换了一条分发渠道——所以 CDN/HTTP 那条路
 * 这里不做。</p>
 *
 * <p>导出的是**当前 index 里的资产**；目录里那些历史孤儿文件（没有 mapKey 指向它们）不在其列。</p>
 */
public final class SceneExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneExportService.class.getSimpleName());
    public static final String DIR_NAME = "habitrain_scene_exports";
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    /** 导出结果，供命令直接回显。 */
    public record ExportResult(int assets, long bytes, int skipped, File directory, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private SceneExportService() {}

    /**
     * 导出全部当前资产。同步兼容入口；服务端命令使用快照在后台执行。
     */
    public static ExportResult exportAll() {
        return export(snapshot(), () -> true);
    }

    public record Snapshot(File storage, Map<String, SceneAssetDescriptor> descriptors) {}

    public static Snapshot snapshot() {
        SceneAssetStore store = SceneAssetStore.getInstance();
        synchronized (store) {
            return new Snapshot(store.getStorageDir(), Map.copyOf(store.getAllDescriptors()));
        }
    }

    /** No world or mutable server state is accessed while copying files. */
    public static ExportResult export(Snapshot snapshot, java.util.function.BooleanSupplier active) {
        File storageDir = snapshot.storage();
        if (storageDir == null) {
            return new ExportResult(0, 0L, 0, null, "世界尚未绑定场景资产目录");
        }
        File worldDir = storageDir.getParentFile();
        File target = new File(worldDir != null ? worldDir : storageDir, DIR_NAME);
        try {
            Files.createDirectories(target.toPath());
            JsonObject manifest = new JsonObject();
            int exported = 0;
            int skipped = 0;
            long bytes = 0L;
            Set<String> usedLowerNames = new HashSet<>();

            for (Map.Entry<String, SceneAssetDescriptor> entry
                    : new java.util.TreeMap<>(snapshot.descriptors()).entrySet()) {
                if (!active.getAsBoolean()) throw new java.io.IOException("导出已取消");
                SceneAssetDescriptor descriptor = entry.getValue();
                if (descriptor == null || !descriptor.isValid()) {
                    skipped++;
                    continue;
                }
                File source = new File(storageDir, descriptor.sha256() + ".hscene");
                if (source == null || !source.isFile()) {
                    skipped++;
                    continue;
                }
                String baseName = safeFileName(entry.getKey()) + "_" + com.habitrain.core.api.scene.asset.SceneAssetCodec
                        .calculateSha256(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
                String candidate = baseName;
                int disambiguator = 1;
                while (!usedLowerNames.add(candidate.toLowerCase(Locale.ROOT))) {
                    candidate = baseName + "_" + descriptor.shortHash()
                            + (disambiguator > 1 ? "_" + disambiguator : "");
                    disambiguator++;
                }
                String fileName = candidate + ".hscene";
                java.nio.file.Path temporary = Files.createTempFile(target.toPath(), ".export-", ".tmp");
                try {
                    Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(temporary) != descriptor.compressedSize()
                            || !com.habitrain.core.api.scene.asset.SceneAssetCodec.calculateSha256(Files.readAllBytes(temporary))
                            .equalsIgnoreCase(descriptor.sha256())) throw new java.io.IOException("导出资产校验失败");
                    if (!active.getAsBoolean()) throw new java.io.IOException("导出已取消");
                    ScenePreparedIndex.move(temporary, new File(target, fileName).toPath());
                } finally { Files.deleteIfExists(temporary); }
                exported++;
                bytes += source.length();

                JsonObject item = new JsonObject();
                item.addProperty("file", fileName);
                item.addProperty("sha256", descriptor.sha256());
                item.addProperty("compressedSize", descriptor.compressedSize());
                item.addProperty("uncompressedSize", descriptor.uncompressedSize());
                item.addProperty("sectionCount", descriptor.sectionCount());
                item.addProperty("mapKey", entry.getKey());
                manifest.add(entry.getKey(), item);
            }

            if (!active.getAsBoolean() || !AtomicJsonFiles.writeJson(new File(target, MANIFEST_FILE_NAME).toPath(), manifest, GSON, true, true))
                throw new java.io.IOException("导出清单写入失败或导出已取消");
            LOGGER.info("场景资产已导出: {} 个 / {} 字节 → {}（跳过 {}）",
                    exported, bytes, target, skipped);
            return new ExportResult(exported, bytes, skipped, target, null);
        } catch (Exception e) {
            LOGGER.error("导出场景资产失败: " + target, e);
            return new ExportResult(0, 0L, 0, target, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 地图键可能带 {@code ::habiscene::} 这类分隔符，直接当文件名在 Windows 上不合法。
     * 同时过滤 Windows 设备保留名并去除首尾点号。
     */
    static String safeFileName(String mapKey) {
        String source = mapKey == null || mapKey.isBlank() ? "scene" : mapKey;
        StringBuilder sb = new StringBuilder(source.length());
        for (char c : source.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        String name = sb.toString();
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1) + "_";
        }
        if (name.isEmpty()) {
            name = "scene";
        }
        int dotIndex = name.indexOf('.');
        String stem = (dotIndex >= 0 ? name.substring(0, dotIndex) : name).toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(stem)) {
            name = "_" + name;
        }
        return name.length() > 96 ? name.substring(0, 96) : name;
    }
}
