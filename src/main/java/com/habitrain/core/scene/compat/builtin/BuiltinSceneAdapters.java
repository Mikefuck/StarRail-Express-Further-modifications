package com.habitrain.core.scene.compat.builtin;

import com.habitrain.core.api.scene.compat.SceneBlockCaptureAdapter;
import com.habitrain.core.api.scene.compat.SceneBlockCaptureAdapters;
import com.habitrain.core.api.scene.compat.SceneCaptureContext;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.compat.SceneBlockCaptureAdapterRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 移动场景通用内置方块捕获适配器注册表（服务端与客户端共享）。
 */
public final class BuiltinSceneAdapters {

    public static final ResourceLocation BUILTIN_STATIC_MODEL_ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "builtin_static_model");

    private BuiltinSceneAdapters() {}

    /**
     * 注册通用服务端方块捕获适配器。
     */
    public static synchronized void registerCommon() {
        if (SceneBlockCaptureAdapterRegistry.getInstance().get(BUILTIN_STATIC_MODEL_ID) != null) return;
        SceneBlockCaptureAdapters.register(new BuiltinStaticModelCaptureAdapter());
    }

    /**
     * 内置静态模型捕获适配器：为具备方块实体但外观为静态几何的方块提供安全外观标记。
     */
    public static final class BuiltinStaticModelCaptureAdapter implements SceneBlockCaptureAdapter {

        @Override
        public ResourceLocation adapterId() {
            return BUILTIN_STATIC_MODEL_ID;
        }

        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public boolean supports(BlockState state, BlockEntity blockEntity) {
            /*
             * 方块状态本身无法证明方块实体渲染器没有额外几何、材质或动画。
             * 过去这里匹配所有方块实体，会把只烘焙到普通 BakedModel 的方块误报为
             * “适配成功”，从而绕过严格发布策略。没有明确的方块/版本白名单时必须
             * 保守拒绝，交给真正的模组专用适配器处理。
             */
            return false;
        }

        @Override
        public SceneRenderPayload captureVisualData(SceneCaptureContext context) {
            CompoundTag tag = new CompoundTag();
            tag.putString("adapter", "builtin_static_model");
            return new SceneRenderPayload(java.util.List.of(), tag);
        }
    }
}
