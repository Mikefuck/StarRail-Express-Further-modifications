package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Scene-only terrain shader: camera-relative fog and world-axis directional light. */
public final class SceneShaders {
    private static ShaderInstance terrain;
    private static Matrix4f model;

    private SceneShaders() {}

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                ResourceLocation.fromNamespaceAndPath("habitrain_core", "scene_terrain"),
                DefaultVertexFormat.BLOCK, shader -> terrain = shader));
    }

    public static ModelScope model(Matrix4f next) {
        return new ModelScope(next);
    }

    public static final class ModelScope implements AutoCloseable {
        private final Matrix4f previous = model;

        private ModelScope(Matrix4f next) { model = next; }
        @Override public void close() { model = previous; }
    }

    static Vector3f localCamera() {
        return model == null ? new Vector3f() : new Matrix4f(model).invert().transformPosition(new Vector3f());
    }

    static ShaderInstance select(ShaderInstance original, float alphaCutoff) {
        if (terrain == null || model == null) return original;
        // Moving scenes own this terrain pass, including when a renderer replaces
        // the vanilla programs. World-axis diffuse light takes priority by design.
        terrain.safeGetUniform("SceneModelMat").set(model);
        terrain.safeGetUniform("AlphaCutoff").set(alphaCutoff);
        return terrain;
    }
}
