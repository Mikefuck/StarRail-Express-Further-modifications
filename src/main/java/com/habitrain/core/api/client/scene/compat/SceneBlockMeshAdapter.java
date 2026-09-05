package com.habitrain.core.api.client.scene.compat;

import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 移动场景客户端方块网格发射适配器。
 * <p>
 * 仅在客户端环境加载。负责根据方块状态和受限 {@link SceneRenderPayload} 向 {@link SceneMaterialSink} 发射静态顶点数据。
 */
@Environment(EnvType.CLIENT)
public interface SceneBlockMeshAdapter {
    /** 适配器唯一标识符（需与服务端的 SceneBlockCaptureAdapter 一致） */
    ResourceLocation adapterId();

    /**
     * 本适配器能够读取的视觉载荷版本。必须与服务端捕获适配器写入的版本一致。
     *
     * <p>默认值保留早期 API 实现的源码兼容性；需要升级载荷格式的适配器应显式覆盖。</p>
     */
    default int dataVersion() {
        return 1;
    }

    /** 判断本适配器是否支持该方块状态与视觉载荷 */
    boolean supports(BlockState state, SceneRenderPayload payload);

    /** 向材质接收槽发射静态网格顶点 */
    SceneBakeResult emitStaticMesh(SceneBakeContext context, SceneMaterialSink materials);
}
