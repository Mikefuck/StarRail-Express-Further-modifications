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
import java.util.Map;

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

    /** 导出结果，供命令直接回显。 */
    public record ExportResult(int assets, long bytes, int skipped, File directory, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private SceneExportService() {}

    /**
     * 导出全部当前资产。全部磁盘操作同步完成——命令本来就是个低频的管理动作，
     * 与其吞掉异常返回"稍后好了"，不如当场给一个准确的结果。
     */
    public static ExportResult exportAll() {
        File storageDir = SceneAssetStore.getInstance().getStorageDir();
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

            for (Map.Entry<String, SceneAssetDescriptor> entry
                    : SceneAssetStore.getInstance().getAllDescriptors().entrySet()) {
                SceneAssetDescriptor descriptor = entry.getValue();
                if (descriptor == null || !descriptor.isValid()) {
                    skipped++;
                    continue;
                }
                File source = SceneAssetStore.getInstance().getAssetFile(descriptor.sha256());
                if (source == null || !source.isFile()) {
                    skipped++;
                    continue;
                }
                String fileName = safeFileName(entry.getKey()) + ".hscene";
                Files.copy(source.toPath(), new File(target, fileName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
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

            AtomicJsonFiles.writeJson(new File(target, MANIFEST_FILE_NAME).toPath(), manifest, GSON, true, true);
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
     * 导出文件名只为人看着方便，客户端认的始终是内容哈希。
     */
    private static String safeFileName(String mapKey) {
        String source = mapKey == null || mapKey.isBlank() ? "scene" : mapKey;
        StringBuilder sb = new StringBuilder(source.length());
        for (char c : source.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        String name = sb.toString();
        return name.length() > 96 ? name.substring(0, 96) : name;
    }
}
