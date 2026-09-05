package com.habitrain.core.scene.compat;

import com.habitrain.core.api.scene.compat.SceneBlockCaptureAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景捕获适配器内部注册表（Common / Dedicated Server 安全，禁止引用客户端类型）。
 */
public final class SceneBlockCaptureAdapterRegistry {
    private static final SceneBlockCaptureAdapterRegistry INSTANCE = new SceneBlockCaptureAdapterRegistry();

    public static SceneBlockCaptureAdapterRegistry getInstance() {
        return INSTANCE;
    }

    private final List<SceneBlockCaptureAdapter> adapters = new CopyOnWriteArrayList<>();

    private SceneBlockCaptureAdapterRegistry() {}

    public synchronized void register(SceneBlockCaptureAdapter adapter) {
        if (adapter == null) return;
        ResourceLocation id = Objects.requireNonNull(adapter.adapterId(), "adapterId");
        if (adapter.dataVersion() <= 0) {
            throw new IllegalArgumentException("Scene capture adapter dataVersion must be positive: " + id);
        }
        SceneBlockCaptureAdapter existing = get(id);
        if (existing == adapter) return;
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate scene capture adapter id: " + id);
        }
        adapters.add(adapter);
    }

    public SceneBlockCaptureAdapter findAdapter(BlockState state, BlockEntity blockEntity) {
        for (SceneBlockCaptureAdapter adapter : adapters) {
            if (adapter.supports(state, blockEntity)) {
                return adapter;
            }
        }
        return null;
    }

    public SceneBlockCaptureAdapter get(ResourceLocation id) {
        if (id == null) return null;
        for (SceneBlockCaptureAdapter adapter : adapters) {
            if (id.equals(adapter.adapterId())) {
                return adapter;
            }
        }
        return null;
    }

    public List<SceneBlockCaptureAdapter> all() {
        return Collections.unmodifiableList(adapters);
    }

    public void clear() {
        adapters.clear();
    }
}
