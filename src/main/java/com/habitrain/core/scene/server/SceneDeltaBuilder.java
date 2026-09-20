package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.asset.SceneAssetDelta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;

/**
 * 发布侧的增量补丁生成：拿"同 mapKey 的上一版资产"当底，为新资产产出一枚 Section 级补丁。
 *
 * <p>整条链路都在捕获工作线程上跑，不占服务端 tick。任何一步不成立（没有上一版、上一版文件
 * 已不在、指纹/数据版本不一致、补丁不比整份小、自检不过）都只是**这次不提供增量**，
 * 正式资产照旧全量发布。</p>
 */
public final class SceneDeltaBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneDeltaBuilder.class.getSimpleName());

    /**
     * 补丁相对整份资产必须小于这个比例才值得提供。
     *
     * <p>0.8 而不是"只要小一点就行"：客户端为补丁多走一次探测往返、多一次 base 解码，
     * 收益要薄到 20% 以下就不划算了。</p>
     */
    public static final double MAX_PATCH_SIZE_RATIO = 0.8;

    private SceneDeltaBuilder() {}

    /**
     * 生成补丁；不值得或不适用时返回 {@code null}。
     *
     * @param mapKey          目标资产的 mapKey（用它找上一版）
     * @param target          新资产的模型
     * @param targetSha256    新资产文件的 SHA-256
     * @param targetBytes     新资产压缩后字节数（用于"补丁够不够小"的判断）
     */
    public static SceneDeltaStore.PatchBlob build(String mapKey, SceneAssetCodec.AssetData target,
                                                  String targetSha256, long targetBytes) {
        if (mapKey == null || mapKey.isBlank() || target == null || targetBytes <= 0) return null;
        try {
            SceneAssetDescriptor previous = SceneAssetStore.getInstance().getDescriptor(mapKey);
            if (previous == null || !previous.isValid() || previous.sha256().equalsIgnoreCase(targetSha256)) {
                return null;
            }
            File baseFile = SceneAssetStore.getInstance().getAssetFile(previous.sha256());
            if (baseFile == null || !baseFile.isFile()) {
                // 上一版文件不在盘上（被手工清理过）——没有底就谈不上增量。
                return null;
            }
            SceneAssetCodec.AssetData base = SceneAssetCodec.decode(Files.readAllBytes(baseFile.toPath()));
            SceneAssetDelta.DeltaData delta = SceneAssetDelta.build(base, previous.sha256(), target, targetSha256);
            if (delta == null) {
                LOGGER.info("本次发布不提供增量：两个版本之间无法构造可信补丁 mapKey={}", mapKey);
                return null;
            }
            byte[] patchBytes = SceneAssetDelta.encode(delta);
            if (patchBytes.length >= targetBytes * MAX_PATCH_SIZE_RATIO) {
                LOGGER.info("本次发布不提供增量：补丁 {} 字节不小于整份 {} 字节的 {}% mapKey={}",
                        patchBytes.length, targetBytes, (int) (MAX_PATCH_SIZE_RATIO * 100), mapKey);
                return null;
            }
            String patchSha256 = SceneAssetCodec.calculateSha256(patchBytes);
            LOGGER.info("已生成场景增量补丁: mapKey={}, {} → 整份 {} 字节 / 补丁 {} 字节（省 {}%）",
                    mapKey, SceneAssetDelta.describe(delta), targetBytes, patchBytes.length,
                    (int) Math.round(100.0 * (targetBytes - patchBytes.length) / targetBytes));
            return new SceneDeltaStore.PatchBlob(previous.sha256(), patchSha256, patchBytes);
        } catch (Exception e) {
            LOGGER.warn("生成场景增量补丁失败，本次按全量发布: mapKey=" + mapKey, e);
            return null;
        }
    }
}
