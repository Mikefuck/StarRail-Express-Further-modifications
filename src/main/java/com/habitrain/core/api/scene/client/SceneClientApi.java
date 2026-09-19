package com.habitrain.core.api.scene.client;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.client.SceneRenderRuntime;
import com.habitrain.core.scene.model.SceneInstance;
import com.habitrain.core.scene.model.SceneRuntimeState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Optional;

/**
 * 移动场景系统的<b>客户端</b>查询与控制 API。
 *
 * <p>供客户端 Mod 读取"服务端现在让我看到哪些场景"：地图级主场景的运行状态、当前维度里
 * 由 API 注册的全部实例、某个实例的网格是否已经就绪。所有方法都是只读的（除了
 * {@link #requestResync()}），可以在渲染线程安全调用。</p>
 *
 * <p>本类只能在客户端加载；专用服务端上引用它会抛 {@link NoClassDefFoundError}。</p>
 */
@Environment(EnvType.CLIENT)
public final class SceneClientApi {
    private static final SceneClientApi INSTANCE = new SceneClientApi();

    public static SceneClientApi instance() {
        return INSTANCE;
    }

    private SceneClientApi() {}

    private static SceneRenderRuntime runtime() {
        return SceneRenderRuntime.getInstance();
    }

    // ------------------------------------------------------------------
    // 地图级场景
    // ------------------------------------------------------------------

    /** 服务端下发的当前地图级场景运行状态（未激活时 {@code isActive()} 为 false）。 */
    public SceneRuntimeState mapSceneState() {
        return runtime().getCurrentState();
    }

    /** 地图级场景是否正在运动。 */
    public boolean isMapSceneActive() {
        SceneRuntimeState state = runtime().getCurrentState();
        return state != null && state.isActive();
    }

    /** 设置页预览是否占用着渲染通道。 */
    public boolean isPreviewActive() {
        return runtime().isPreviewActive();
    }

    // ------------------------------------------------------------------
    // API 场景实例
    // ------------------------------------------------------------------

    /** 本地已知的全部 API 场景实例（所有维度，按 priority 排序）。 */
    public List<SceneInstance> instances() {
        return List.copyOf(runtime().dynamicInstances());
    }

    /** 只返回属于本地玩家当前维度的实例。 */
    public List<SceneInstance> instancesInCurrentDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();
        String dimensionKey = mc.level.dimension().location().toString();
        return runtime().dynamicInstances().stream()
                .filter(instance -> instance.belongsTo(dimensionKey))
                .toList();
    }

    /** 本地已知的实例数量。 */
    public int instanceCount() {
        return runtime().dynamicInstanceCount();
    }

    /** 按 ID 查询本地已知的实例。 */
    public Optional<SceneInstance> instance(String instanceId) {
        return Optional.ofNullable(runtime().dynamicInstance(instanceId));
    }

    /** 某实例的网格是否已经烘焙完成（可以立即绘制）。 */
    public boolean isMeshReady(String instanceId) {
        return runtime().isDynamicInstanceMeshReady(instanceId);
    }

    /** 本地已知的资产描述符（按资产键查询，未见过时返回 {@link SceneAssetDescriptor#EMPTY}）。 */
    public SceneAssetDescriptor manifest(String assetKey) {
        return runtime().getManifest(assetKey);
    }

    /** 当前所有活跃场景占用的 GPU 网格估算字节数。 */
    public long meshBytes() {
        return runtime().totalMeshBytes();
    }

    /**
     * 请求服务端重新下发当前维度的全部 API 场景实例。
     *
     * <p>客户端在"清空过本地状态但没换会话"之后应当调用一次（本模组在对局结束时已自动调用）。</p>
     */
    public void requestResync() {
        com.habitrain.core.scene.client.SceneClientRuntime.requestInstanceResync();
    }
}
