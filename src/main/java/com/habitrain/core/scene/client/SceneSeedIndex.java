package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 整合包预置目录索引：把 {@code habitrain_scene_seed/} 里的 {@code .hscene} 按内容哈希登记，
 * 命中时客户端一个字节都不用从游戏服务器下。
 *
 * <p><b>文件名永远不作数</b>——只认文件内容的 SHA-256。整合包作者把文件改名成地图名是为了
 * 人看得懂，不是为了被信任。</p>
 *
 * <p>扫描在独立线程上做一次：{@link SceneAssetCache} 的单线程 IO 池上已经有整文件校验与解码，
 * 在那里哈希一整个目录会把所有资产的加载一起堵住。索引没建好时命中查询只是"没命中"，
 * 走正常下载，不影响任何正确性。</p>
 */
final class SceneSeedIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneSeedIndex.class.getSimpleName());
    private static final String SUFFIX = ".hscene";

    private final File dir;
    /** hash → 文件；只在扫描线程写、其它线程读，因此用不可变替换而不是增量修改。 */
    private volatile Map<String, File> byHash = Map.of();
    private volatile boolean scanned;
    private final AtomicBoolean scanStarted = new AtomicBoolean();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();

    SceneSeedIndex(File dir) {
        this.dir = dir;
    }

    File directory() {
        return dir;
    }

    /**
     * 开始（或复用）扫描。多次调用只会真正扫一次；目录不存在就直接标记完成。
     */
    void ensureScannedAsync() {
        if (!scanStarted.compareAndSet(false, true)) return;
        Thread thread = new Thread(this::scan, "HabiTrain-SceneSeed-Scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void scan() {
        if (scanned) return;
        Map<String, File> found = new HashMap<>();
        try {
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
                if (files != null) {
                    for (File file : files) {
                        if (!file.isFile() || file.length() <= 0
                                || file.length() > SceneAssetCodec.MAX_COMPRESSED_BYTES) continue;
                        try {
                            String hash = SceneAssetCodec.calculateSha256(Files.readAllBytes(file.toPath()));
                            found.putIfAbsent(hash, file);
                        } catch (Exception e) {
                            LOGGER.warn("预置场景资产读取失败，已跳过: {}", file.getName(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("扫描场景资产预置目录失败: " + dir, e);
        } finally {
            byHash = Collections.unmodifiableMap(found);
            scanned = true;
            if (!found.isEmpty()) {
                LOGGER.info("场景资产预置目录已就绪: {} 个资产（{}）", found.size(), dir);
            }
        }
    }

    /**
     * 是否已经扫完。没扫完时 {@link #find} 一律返回 null——宁可去下载，也不要卡住加载。
     */
    boolean isReady() {
        return scanned;
    }

    /**
     * 按内容哈希找预置文件。调用方仍必须再校验一次内容（这里只保证"扫描时它是对的"）。
     */
    File find(String sha256) {
        if (!scanned || sha256 == null) return null;
        File file = byHash.get(sha256.toLowerCase());
        if (file == null || rejected.contains(sha256.toLowerCase())) return null;
        return file;
    }

    /** 内容与文件名不符的文件：记下来，避免每次加载都重新读一遍坏文件。 */
    void reject(String sha256) {
        if (sha256 != null) rejected.add(sha256.toLowerCase());
    }
}
