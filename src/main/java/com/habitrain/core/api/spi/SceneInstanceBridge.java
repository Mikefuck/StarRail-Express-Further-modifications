package com.habitrain.core.api.spi;

import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.SceneInstanceView;
import com.habitrain.core.api.scene.SceneListener;
import com.habitrain.core.api.scene.SceneSpawnResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 场景实例注册表桥接接口（SPI）。
 *
 * <p>{@code api.scene.SceneApi} 需要读写运行期实例表，但这套实现位于
 * {@code scene.server} 实现层。把它收成接口并由实现类
 * （{@code scene.server.SceneInstanceService}）在 core 启动时装配，即可去掉
 * {@code api → scene.server} 的反向依赖（审核 A2）。
 *
 * <p>所有方法都有安全的默认实现（空值 / 不动作），未装配时公开层退化为「没有场景系统」，
 * 不会 NPE——单元测试可直接使用公开层。
 */
public interface SceneInstanceBridge {

    default MinecraftServer server() {
        return null;
    }

    default boolean isGlobalEnabled() {
        return false;
    }

    default int count() {
        return 0;
    }

    default int countIn(String dimensionKey) {
        return 0;
    }

    default List<? extends SceneInstanceView> all() {
        return List.of();
    }

    default List<? extends SceneInstanceView> byDimension(String dimensionKey) {
        return List.of();
    }

    default List<? extends SceneInstanceView> byOwner(String ownerId) {
        return List.of();
    }

    default List<? extends SceneInstanceView> byTag(String tag) {
        return List.of();
    }

    default Optional<? extends SceneInstanceView> get(String instanceId) {
        return Optional.empty();
    }

    default boolean exists(String instanceId) {
        return false;
    }

    default SceneSpawnResult spawn(SceneInstanceSpec spec) {
        return SceneSpawnResult.failure(
                SceneSpawnResult.SceneSpawnStatus.SERVER_UNAVAILABLE, "", "scene system not installed");
    }

    default SceneSpawnResult spawn(@Nullable ServerLevel level, SceneInstanceSpec spec) {
        return SceneSpawnResult.failure(
                SceneSpawnResult.SceneSpawnStatus.SERVER_UNAVAILABLE, "", "scene system not installed");
    }

    default SceneSpawnResult upsert(SceneInstanceSpec spec) {
        return SceneSpawnResult.failure(
                SceneSpawnResult.SceneSpawnStatus.SERVER_UNAVAILABLE, "", "scene system not installed");
    }

    default SceneSpawnResult upsert(@Nullable ServerLevel level, SceneInstanceSpec spec) {
        return SceneSpawnResult.failure(
                SceneSpawnResult.SceneSpawnStatus.SERVER_UNAVAILABLE, "", "scene system not installed");
    }

    default boolean despawn(String instanceId) {
        return false;
    }

    default int despawnAll(String ownerId) {
        return 0;
    }

    default int despawnByTag(String tag) {
        return 0;
    }

    default int despawnMatching(Predicate<SceneInstanceView> predicate) {
        return 0;
    }

    default int despawnInDimension(String dimensionKey) {
        return 0;
    }

    default int despawnAllInstances() {
        return 0;
    }

    default boolean resync(String instanceId) {
        return false;
    }

    default int resyncDimension(String dimensionKey) {
        return 0;
    }

    default void resyncAll() {
    }

    default boolean mutate(String instanceId, Consumer<SceneInstanceSpec.Builder> mutation) {
        return false;
    }

    default boolean restart(String instanceId) {
        return false;
    }

    default boolean moveToDimension(String instanceId, String dimensionKey) {
        return false;
    }

    default void addListener(SceneListener listener) {
    }

    default void removeListener(SceneListener listener) {
    }

    default JsonObject toJson() {
        return new JsonObject();
    }

    default List<String> describe() {
        return List.of();
    }

    default Collection<? extends SceneInstanceView> snapshot() {
        return List.of();
    }
}
