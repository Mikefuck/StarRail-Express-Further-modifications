package com.habitrain.core.scene.client.compat;

import com.habitrain.core.api.client.scene.compat.SceneBlockMeshAdapter;
import com.habitrain.core.api.scene.compat.SceneRenderPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端方块网格发射适配器注册表（仅在 Client 环境初始化）。
 */
@Environment(EnvType.CLIENT)
public final class SceneBlockMeshAdapterRegistry {
    private static final SceneBlockMeshAdapterRegistry INSTANCE = new SceneBlockMeshAdapterRegistry();

    public static SceneBlockMeshAdapterRegistry getInstance() {
        return INSTANCE;
    }

    private final List<SceneBlockMeshAdapter> adapters = new CopyOnWriteArrayList<>();

    private SceneBlockMeshAdapterRegistry() {}

    public synchronized void register(SceneBlockMeshAdapter adapter) {
        if (adapter == null) return;
        ResourceLocation id = Objects.requireNonNull(adapter.adapterId(), "adapterId");
        if (adapter.dataVersion() <= 0) {
            throw new IllegalArgumentException("Scene mesh adapter dataVersion must be positive: " + id);
        }
        SceneBlockMeshAdapter existing = get(id);
        if (existing == adapter) return;
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate scene mesh adapter id: " + id);
        }
        adapters.add(adapter);
    }

    public SceneBlockMeshAdapter findAdapter(BlockState state, SceneRenderPayload payload) {
        for (SceneBlockMeshAdapter adapter : adapters) {
            if (adapter.supports(state, payload)) {
                return adapter;
            }
        }
        return null;
    }

    public SceneBlockMeshAdapter get(ResourceLocation id) {
        if (id == null) return null;
        for (SceneBlockMeshAdapter adapter : adapters) {
            if (id.equals(adapter.adapterId())) {
                return adapter;
            }
        }
        return null;
    }

    public List<SceneBlockMeshAdapter> all() {
        return Collections.unmodifiableList(adapters);
    }

    public void clear() {
        adapters.clear();
    }
}
