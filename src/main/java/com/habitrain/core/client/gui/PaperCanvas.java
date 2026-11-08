package com.habitrain.core.client.gui;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 与窗口同尺寸的离屏画布：先把车票（或 MVP 人像）按屏幕 GUI 坐标画进来，再当作纹理整体贴回。
 *
 * <p>画布清空为全透明，内容是预乘 alpha（GUI 默认混合下在透明底上作画的结果）。
 * {@link #begin}/{@link #end} 之间的绘制坐标、投影、裁剪与直接画到屏幕上完全相同。</p>
 */
final class PaperCanvas {
    private TextureTarget target;
    private final boolean useDepth;
    private com.habitrain.core.client.render.ClientRenderState previousRenderState;
    PaperCanvas() { this(true); }
    PaperCanvas(boolean useDepth) { this.useDepth = useDepth; }
    private FramebufferState previousFramebuffer;

    /** 清空并绑定画布；之后的 GuiGraphics 绘制都落在画布上。 */
    void begin(GuiGraphics g) {
        g.flush();
        if (previousFramebuffer != null) throw new IllegalStateException("Canvas already bound");
        previousFramebuffer = new FramebufferState();
        previousRenderState = com.habitrain.core.client.render.ClientRenderState.capture();
        try {
        Window window = Minecraft.getInstance().getWindow();
        int w = Math.max(1, window.getWidth());
        int h = Math.max(1, window.getHeight());
        if (this.target == null) {
            this.target = new TextureTarget(w, h, useDepth, Minecraft.ON_OSX);
            this.target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        } else if (this.target.width != w || this.target.height != h) {
            this.target.resize(w, h, Minecraft.ON_OSX);
        }
        if (this.target.filterMode != GL11.GL_LINEAR) {
            this.target.setFilterMode(GL11.GL_LINEAR);
        }
        this.target.clear(Minecraft.ON_OSX);
        this.target.bindWrite(true);
        } catch (RuntimeException | Error failure) {
            previousFramebuffer.close();
            previousFramebuffer = null;
            previousRenderState.close();
            previousRenderState = null;
            throw failure;
        }
    }

    /** 结束作画，切回先前的帧缓冲（或主帧缓冲）。 */
    void end(GuiGraphics g) {
        try {
            g.flush();
        } finally {
            FramebufferState previous = previousFramebuffer;
            previousFramebuffer = null;
            try {
                if (previous != null) previous.close();
            } finally {
                if (previousRenderState != null) { previousRenderState.close(); previousRenderState = null; }
            }
        }
    }

    boolean ready() {
        return this.target != null;
    }

    int textureId() {
        return this.target == null ? 0 : this.target.getColorTextureId();
    }

    void close() {
        if (this.target != null) {
            this.target.destroyBuffers();
            this.target = null;
        }
    }

    /**
     * 把画布上 [u0,u1]×[v0,v1] 的一块原样贴到当前 pose 下的矩形 (x0,y0)-(x1,y1)（预乘 alpha 混合）。
     * shader 为 null 时用原版 position_tex_color，alpha 以预乘方式写进顶点色。
     */
    static void blit(GuiGraphics g, int texture, ShaderInstance shader, float x0, float y0, float x1, float y1,
                     float u0, float v0, float u1, float v1, float alpha) {
        try (var stateGuard = com.habitrain.core.client.render.ClientRenderState.capture()) {
        if (texture == 0 || alpha <= 0.002f) {
            return;
        }
        g.flush();
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        }
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        try {
            Matrix4f matrix = g.pose().last().pose();
            int color = PaperSheet.argb(alpha, alpha, alpha, alpha);
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            MeshData mesh = null;
            try {
                buffer.addVertex(matrix, x0, y0, 0.0f).setUv(u0, v0).setColor(color);
                buffer.addVertex(matrix, x0, y1, 0.0f).setUv(u0, v1).setColor(color);
                buffer.addVertex(matrix, x1, y1, 0.0f).setUv(u1, v1).setColor(color);
                buffer.addVertex(matrix, x1, y0, 0.0f).setUv(u1, v0).setColor(color);
                mesh = buffer.build();
                if (mesh != null) {
                    BufferUploader.drawWithShader(mesh);
                    mesh = null;
                }
            } finally {
                if (mesh != null) {
                    mesh.close();
                }
            }
        } finally {
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    
        }
    }

    // ==================== 坐标换算 ====================

    /** GUI 横坐标 → 画布纹理 u。 */
    static float u(float guiX) {
        Window window = Minecraft.getInstance().getWindow();
        return (float) (guiX * window.getGuiScale() / Math.max(1, window.getWidth()));
    }

    /** GUI 纵坐标 → 画布纹理 v（纹理原点在左下）。 */
    static float v(float guiY) {
        Window window = Minecraft.getInstance().getWindow();
        return 1.0f - (float) (guiY * window.getGuiScale() / Math.max(1, window.getHeight()));
    }
}
