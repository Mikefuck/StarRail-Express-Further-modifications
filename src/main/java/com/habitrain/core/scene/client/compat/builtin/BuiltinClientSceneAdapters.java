package com.habitrain.core.scene.client.compat.builtin;

import com.habitrain.core.api.client.scene.compat.SceneBakeContext;
import com.habitrain.core.api.client.scene.compat.SceneBakeResult;
import com.habitrain.core.api.client.scene.compat.SceneBlockMeshAdapter;
import com.habitrain.core.api.client.scene.compat.SceneBlockMeshAdapters;
import com.habitrain.core.api.client.scene.compat.SceneMaterialSink;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import com.habitrain.core.scene.compat.builtin.BuiltinSceneAdapters;
import com.habitrain.core.scene.client.compat.SceneBlockMeshAdapterRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 移动场景客户端内置方块网格发射适配器。
 */
@Environment(EnvType.CLIENT)
public final class BuiltinClientSceneAdapters {

    private BuiltinClientSceneAdapters() {}

    /**
     * 注册客户端内置网格适配器。
     */
    public static synchronized void registerClient() {
        if (SceneBlockMeshAdapterRegistry.getInstance().get(BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID) != null) return;
        SceneBlockMeshAdapters.register(new BuiltinStaticModelMeshAdapter());
    }

    /**
     * 为旧资产保留 ID 的占位适配器。没有明确白名单时不会接管任何方块实体。
     */
    public static final class BuiltinStaticModelMeshAdapter implements SceneBlockMeshAdapter {

        @Override
        public ResourceLocation adapterId() {
            return BuiltinSceneAdapters.BUILTIN_STATIC_MODEL_ID;
        }

        @Override
        public boolean supports(BlockState state, SceneRenderPayload payload) {
            // Common 侧不再对未知方块实体签发这个占位载荷；客户端同样不得把它
            // 当作真实的方块实体视觉适配结果。
            return false;
        }

        @Override
        public SceneBakeResult emitStaticMesh(SceneBakeContext context, SceneMaterialSink materials) {
            return SceneBakeResult.UNSUPPORTED;
        }
    }
}
