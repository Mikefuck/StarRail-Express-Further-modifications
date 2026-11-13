package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.client.SceneBuildScheduler;
import com.habitrain.core.scene.client.SceneMeshBuilder;
import com.habitrain.core.scene.client.SceneMeshSet;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the single GPU mesh shared by every board (they all show the same last match).
 *
 * <p>Rebuilds are keyed by map fingerprint and delivered section count. The moving fan openings
 * are applied at draw time, so avatar motion and camera turns never rebuild the map. While patches
 * are still arriving, partial rebuilds are coalesced (one at a time, at most every few seconds);
 * the final complete map always rebuilds immediately. Late results from superseded builds, level
 * switches, disconnects or resource reloads are closed instead of installed.</p>
 */
final class ReplayDioramaMesh {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayDioramaMesh.class.getSimpleName());
    private static final long PARTIAL_REBUILD_NS = 2_500_000_000L;
    private static final long IDLE_RELEASE_NS = 45_000_000_000L;

    record Key(String fingerprint, int present) {
        boolean sameMap(Key other) { return other != null && fingerprint.equals(other.fingerprint); }
    }

    private static @Nullable SceneMeshSet mesh;
    private static @Nullable Key builtKey;
    private static @Nullable CompletableFuture<SceneMeshBuilder.MeshBuildResult> pending;
    private static @Nullable Key pendingKey;
    private static Object ticket = new Object();
    private static long lastSubmit, lastUse;

    private ReplayDioramaMesh() {}

    /** Mesh to draw this frame (possibly an older partial one), or null. */
    static @Nullable SceneMeshSet current() {
        lastUse = System.nanoTime();
        SceneMeshSet m = mesh;
        return m == null || m.isClosed() || m.isEmpty() ? null : m;
    }

    static @Nullable Key builtKey() { return builtKey; }

    static void update(@Nullable SceneAssetCodec.AssetData asset, int present, int total) {
        lastUse = System.nanoTime();
        if (asset == null || asset.sections.isEmpty()) return;
        Key want = new Key(asset.fingerprint, present);
        if (want.equals(builtKey) || want.equals(pendingKey)) return;
        if (builtKey != null && !builtKey.sameMap(want)) closeMesh();
        if (pending != null) {
            if (pendingKey != null && pendingKey.sameMap(want)) return;
            cancelPending();
        }
        long now = System.nanoTime();
        boolean complete = present >= total;
        boolean sameGeometryClass = builtKey != null && builtKey.sameMap(want);
        if (sameGeometryClass && !complete && now - lastSubmit < PARTIAL_REBUILD_NS) return;
        submit(asset, want);
    }

    private static void submit(SceneAssetCodec.AssetData asset, Key key) {
        Object token = new Object();
        ticket = token;
        lastSubmit = System.nanoTime();
        CompletableFuture<SceneMeshBuilder.MeshBuildResult> future;
        try {
            future = SceneMeshBuilder.buildCutawayMeshAsync(asset, SceneBuildScheduler.Priority.ADDITIONAL);
        } catch (RuntimeException failure) {
            LOGGER.warn("Replay diorama mesh submission failed", failure);
            return;
        }
        pending = future;
        pendingKey = key;
        future.whenComplete((result, failure) -> {
            Runnable install = () -> install(token, key, future, result, failure);
            Minecraft client = Minecraft.getInstance();
            if (client.isSameThread()) install.run();
            else client.execute(install);
        });
    }

    private static void install(Object token, Key key, CompletableFuture<?> future,
                                SceneMeshBuilder.MeshBuildResult result, Throwable failure) {
        SceneMeshSet built = result == null ? null : result.meshSet();
        if (token != ticket) {
            if (built != null) built.close();
            return;
        }
        if (pending == future) { pending = null; pendingKey = null; }
        if (failure != null || built == null || built.isClosed() || built.isEmpty()) {
            if (built != null) built.close();
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                LOGGER.warn("Replay diorama mesh build failed", failure);
            }
            // Remember the key so a failing input does not resubmit every frame.
            builtKey = key;
            return;
        }
        SceneMeshSet previous = mesh;
        mesh = built;
        builtKey = key;
        if (previous != null && previous != built) previous.close();
    }

    /** Releases GPU memory when no board has been rendered for a while. */
    static void tickIdle() {
        if ((mesh != null || pending != null) && System.nanoTime() - lastUse > IDLE_RELEASE_NS) reset();
    }

    private static void cancelPending() {
        CompletableFuture<?> p = pending;
        pending = null;
        pendingKey = null;
        ticket = new Object();
        if (p != null) p.cancel(false);
    }

    private static void closeMesh() {
        SceneMeshSet m = mesh;
        mesh = null;
        builtKey = null;
        if (m != null) m.close();
    }

    /** Disconnect, level change, new incompatible round or resource reload. */
    static void reset() {
        cancelPending();
        closeMesh();
        lastSubmit = 0;
    }

    static boolean matches(@Nullable SceneAssetCodec.AssetData asset) {
        return asset != null && builtKey != null && Objects.equals(builtKey.fingerprint(), asset.fingerprint);
    }
}
