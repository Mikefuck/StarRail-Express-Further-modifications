package com.habitrain.core.api.client.scene.compat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 客户端网格适配器烘焙执行结果。
 */
@Environment(EnvType.CLIENT)
public enum SceneBakeResult {
    /** 烘焙成功并已发射静态顶点 */
    SUCCESS,
    /** 适配器主动跳过该方块（例如作为空洞处理） */
    SKIPPED,
    /** 适配器不支持该方块或该版本载荷 */
    UNSUPPORTED,
    /** 缺失关键纹理或图集资源 */
    MISSING_TEXTURE
}
