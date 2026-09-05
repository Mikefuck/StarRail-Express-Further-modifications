package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Records client render-distance inputs and the far plane from the actual world projection. */
@Environment(EnvType.CLIENT)
public final class SceneProjectionDiagnostics {
    public static final int VANILLA_CLIENT_MAXIMUM_CHUNKS = 32;
    private static volatile double measuredFarPlaneBlocks = Double.NaN;
    private static volatile int serverViewDistanceChunks = Integer.MAX_VALUE;

    private SceneProjectionDiagnostics() {}

    public record Snapshot(int clientMaximumChunks, int clientConfiguredChunks,
                           int effectiveChunks, int serverLimitChunks,
                           double farPlaneBlocks, boolean measured,
                           boolean irisLoaded) {}

    public static void recordProjection(Matrix4f projection) {
        if (projection == null) return;
        double far = estimatePerspectiveFarPlane(projection.m22(), projection.m32());
        if (Double.isFinite(far) && far > 0.0) measuredFarPlaneBlocks = far;
    }

    /** Standard OpenGL/JOML perspective extraction; returns NaN for unsupported matrices. */
    public static double estimatePerspectiveFarPlane(double m22, double m32) {
        double denominator = m22 + 1.0;
        if (!Double.isFinite(m22) || !Double.isFinite(m32) || Math.abs(denominator) < 1.0e-7) {
            return Double.NaN;
        }
        double far = m32 / denominator;
        return Double.isFinite(far) && far > 0.0 ? far : Double.NaN;
    }

    public static void setServerViewDistanceChunks(int chunks) {
        if (chunks > 0) serverViewDistanceChunks = chunks;
    }

    public static Snapshot snapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        int configured = minecraft != null && minecraft.options != null
                ? minecraft.options.renderDistance().get() : 12;
        int serverLimit = serverViewDistanceChunks == Integer.MAX_VALUE
                ? configured : serverViewDistanceChunks;
        int effective = Math.max(1, Math.min(configured, serverLimit));
        boolean measured = Double.isFinite(measuredFarPlaneBlocks) && measuredFarPlaneBlocks > 0.0;
        double far = measured ? measuredFarPlaneBlocks : effective * 64.0;
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        return new Snapshot(VANILLA_CLIENT_MAXIMUM_CHUNKS, configured, effective,
                serverLimit, far, measured, iris);
    }

    public static void reset() {
        measuredFarPlaneBlocks = Double.NaN;
        serverViewDistanceChunks = Integer.MAX_VALUE;
    }
}
