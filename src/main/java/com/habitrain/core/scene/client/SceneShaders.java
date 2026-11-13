package com.habitrain.core.scene.client;

import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Scene-only terrain shader: camera-relative fog and world-axis directional light. */
public final class SceneShaders {
    private static ShaderInstance terrain;
    private static Matrix4f model;
    private static int minimumLight;

    private SceneShaders() {}

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                ResourceLocation.fromNamespaceAndPath("habitrain_core", "scene_terrain"),
                DefaultVertexFormat.BLOCK, shader -> terrain = shader));
    }

    public static ModelScope model(Matrix4f next) {
        return model(next, 0);
    }

    /** Supplies an optional packed light floor for this draw, without changing the mesh. */
    public static ModelScope model(Matrix4f next, int packedMinimumLight) {
        return new ModelScope(next, packedMinimumLight);
    }

    public static final class ModelScope implements AutoCloseable {
        private final Matrix4f previous = model;
        private final int previousMinimumLight = minimumLight;

        private ModelScope(Matrix4f next, int packedMinimumLight) {
            model = next;
            minimumLight = packedMinimumLight;
        }

        @Override public void close() {
            model = previous;
            minimumLight = previousMinimumLight;
        }
    }

    static Vector3f localCamera() {
        return model == null ? new Vector3f() : new Matrix4f(model).invert().transformPosition(new Vector3f());
    }

    /**
     * Optional mesh-local clip plane: fragments with {@code dot(localPos, n) > d} are discarded.
     * The replay board's scan-in reveal uses it independently of the avatar fan openings.
     */
    public static ClipScope clip(float nx, float ny, float nz, float d) {
        return new ClipScope(new float[]{nx, ny, nz, d});
    }

    public static final class ClipScope implements AutoCloseable {
        private final float[] previous = clip;

        private ClipScope(float[] next) { clip = next; }
        @Override public void close() { clip = previous; }
    }

    private static final float[] NO_CLIP = {0.0f, 0.0f, 0.0f, 1.0f};
    private static float[] clip = NO_CLIP;

    /** Four independent vertical walls in source coordinates, in addition to scan and fan cuts. */
    public static BoundsScope bounds(float minX, float minZ, float maxX, float maxZ) {
        return new BoundsScope(new float[]{minX, minZ, maxX, maxZ});
    }

    private static float[] bounds = {-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};

    public static final class BoundsScope implements AutoCloseable {
        private final float[] previous = bounds;
        private BoundsScope(float[] next) { bounds = next; }
        @Override public void close() { bounds = previous; }
    }

    private static ReplayFanCutaway fans;

    /** Scoped to one board: its display transform determines the viewer-facing fan direction. */
    public static FanScope fans(ReplayFanCutaway next) { return new FanScope(next); }

    public static final class FanScope implements AutoCloseable {
        private final ReplayFanCutaway previous = fans;
        private FanScope(ReplayFanCutaway next) { fans = next; }
        @Override public void close() { fans = previous; }
    }

    static ShaderInstance select(ShaderInstance original, float alphaCutoff) {
        if (terrain == null || model == null) return original;
        // Moving scenes own this terrain pass, including when a renderer replaces
        // the vanilla programs. World-axis diffuse light takes priority by design.
        terrain.safeGetUniform("SceneModelMat").set(model);
        terrain.safeGetUniform("AlphaCutoff").set(alphaCutoff);
        terrain.safeGetUniform("SceneClip").set(clip[0], clip[1], clip[2], clip[3]);
        terrain.safeGetUniform("SceneBounds").set(bounds[0], bounds[1], bounds[2], bounds[3]);
        int fanCount = fans == null ? 0 : fans.count();
        terrain.safeGetUniform("ReplayFanCount").set(fanCount);
        if (fanCount > 0) {
            terrain.safeGetUniform("ReplayFanAnchors").set(fans.anchors());
            terrain.safeGetUniform("ReplayFanView").set(fans.directionX(), fans.directionZ(),
                    ReplayFanCutaway.HALF_ANGLE_TANGENT, 0f);
        }
        terrain.safeGetUniform("SceneLightFloor").set(LightTexture.block(minimumLight) << 4,
                LightTexture.sky(minimumLight) << 4);
        return terrain;
    }
}
