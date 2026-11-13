package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One frame of board rendering: shared clock / stage, buffers and deferred passes.
 *
 * <p>Pass order is fixed by the renderer: textured tray (opaque) → diorama VBO → flat fills →
 * deferred models (heads, weapon items) → additive glow → deferred text. Fills and glow live in
 * dedicated buffers so painters can emit them in any order; models and text are deferred because
 * they switch render types per texture.</p>
 */
final class ReplayFrame {
    final Minecraft mc;
    final ClientLevel level;
    final Camera camera;
    final Vector3f right, up;
    final MultiBufferSource.BufferSource buffers;
    final RenderType trayType;
    final double anim;
    final @Nullable ReplayBoardModel model;
    final @Nullable ReplayTimeline.Frame frame;
    final @Nullable ReplayStagePlan.Stage stage;
    final double replayTick;
    final ReplayFocusPlan.Pose focus;
    final boolean blackout;
    final boolean hasRound;
    final int presentSections, totalSections;
    private final List<Runnable> models = new ArrayList<>();
    private final List<Runnable> texts = new ArrayList<>();
    private MapScope mapScope;
    private MultiBufferSource drawingBuffers;

    ReplayFrame(Minecraft mc, ClientLevel level, Camera camera, MultiBufferSource.BufferSource buffers, RenderType trayType,
                double anim, @Nullable ReplayBoardModel model, @Nullable ReplayTimeline.Frame frame,
                boolean hasRound, int presentSections, int totalSections) {
        this.mc = mc;
        this.level = level;
        this.camera = camera;
        this.right = new Vector3f(camera.getLeftVector()).negate();
        this.up = new Vector3f(camera.getUpVector());
        this.buffers = buffers;
        this.trayType = trayType;
        this.anim = anim;
        this.model = model;
        this.frame = frame;
        this.stage = frame == null ? null : ReplayStagePlan.stage(frame);
        this.replayTick = frame == null ? 0 : frame.replayTick();
        this.focus = model == null ? ReplayFocusPlan.WIDE : model.focus.at(frame);
        this.drawingBuffers = buffers;
        this.blackout = model != null && frame != null && frame.phase() == ReplayTimeline.Phase.PLAY && model.blackoutAt(replayTick);
        this.hasRound = hasRound;
        this.presentSections = presentSections;
        this.totalSections = totalSections;
    }

    VertexConsumer glow() { return mapScope == null ? buffers.getBuffer(ReplayDraw.GLOW) : mapScope.glow; }
    VertexConsumer fill() { return mapScope == null ? buffers.getBuffer(ReplayDraw.FILL) : mapScope.fill; }
    VertexConsumer tray() { return buffers.getBuffer(trayType); }
    MultiBufferSource modelBuffers() { return drawingBuffers; }

    /** Clip all map decorations; fixed tray UI and honors are collected outside this scope. */
    MapScope mapScope(ReplayBoardSite site) { return new MapScope(site); }

    private record Viewport(Matrix4f inverseBoard, float hw, float hd) {
        ReplayClippedConsumer wrap(VertexConsumer source) {
            return new ReplayClippedConsumer(source, inverseBoard, hw, hd);
        }
    }

    final class MapScope implements AutoCloseable {
        final Viewport viewport;
        final ReplayClippedConsumer fill, glow;

        private MapScope(ReplayBoardSite site) {
            if (mapScope != null) throw new IllegalStateException("Nested replay map scope");
            viewport = new Viewport(new Matrix4f(site.board()).invert(), site.hw() - ReplayBoardSite.FRAME - 0.04f,
                    site.hd() - ReplayBoardSite.FRAME - 0.04f);
            fill = viewport.wrap(buffers.getBuffer(ReplayDraw.FILL));
            glow = viewport.wrap(buffers.getBuffer(ReplayDraw.GLOW));
            mapScope = this;
        }

        @Override public void close() {
            try { fill.close(); glow.close(); }
            finally { mapScope = null; }
        }
    }

    /** Flush before switching a dynamic vanilla buffer, which may invalidate its old consumer. */
    private final class ViewportBuffers implements MultiBufferSource, AutoCloseable {
        private final Viewport viewport;
        private ReplayClippedConsumer current;
        private ViewportBuffers(Viewport viewport) { this.viewport = viewport; }
        @Override public VertexConsumer getBuffer(RenderType type) {
            close();
            current = viewport.wrap(buffers.getBuffer(type));
            return current;
        }
        @Override public void close() {
            if (current != null) { current.close(); current = null; }
        }
    }

    /** Defers a model draw; the consumer receives a fresh pose stack positioned at {@code m}. */
    void model(Matrix4f m, Consumer<PoseStack> draw) {
        Matrix4f copy = new Matrix4f(m);
        Viewport viewport = mapScope == null ? null : mapScope.viewport;
        models.add(() -> {
            PoseStack stack = new PoseStack();
            stack.mulPose(copy);
            try (var clipped = viewport == null ? null : new ViewportBuffers(viewport)) {
                drawingBuffers = clipped == null ? buffers : clipped;
                draw.accept(stack);
            } finally { drawingBuffers = buffers; }
        });
    }

    /**
     * Defers text in the local XY plane of {@code m} (visible from +Z). {@code align}: -1 left, 0 centre, 1 right.
     * Text wider than {@code maxWidth} (blocks, 0 = unlimited) is scaled down to fit.
     */
    void text(Matrix4f m, Component text, float x, float y, float z, float scale, int color, int align,
              boolean shadow, float maxWidth) {
        if (((color >>> 24) & 0xFF) < 8) return;
        Matrix4f copy = new Matrix4f(m);
        Viewport viewport = mapScope == null ? null : mapScope.viewport;
        texts.add(() -> {
            Font font = mc.font;
            float width = font.width(text);
            float s = scale;
            if (maxWidth > 0 && width * s > maxWidth) s = maxWidth / Math.max(1f, width);
            Matrix4f mm = new Matrix4f(copy).translate(x, y, z).scale(s, -s, s);
            float dx = align < 0 ? 0 : align == 0 ? -width / 2f : -width;
            try (var clipped = viewport == null ? null : new ViewportBuffers(viewport)) {
                font.drawInBatch(text, dx, -font.lineHeight / 2f, color, shadow, mm, clipped == null ? buffers : clipped,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            }
        });
    }

    /** Camera-facing text at a camera-relative point. */
    void billboardText(Vector3f at, Component text, float scale, int color) {
        Matrix4f m = new Matrix4f().translate(at).rotate(camera.rotation());
        text(m, text, 0, 0, 0, scale, color, 0, false, 0);
    }

    void flushModels() {
        for (Runnable r : models) r.run();
        models.clear();
    }

    void flushTexts() {
        for (Runnable r : texts) r.run();
        texts.clear();
    }

    /** Camera-relative transform of the board frame (origin one block above the marker, +Z = front). */
    static Matrix4f boardMatrix(BlockPos pos, Direction facing, double camX, double camY, double camZ) {
        return new Matrix4f().translate((float) (pos.getX() + 0.5 - camX), (float) (pos.getY() + 1.0 - camY),
                (float) (pos.getZ() + 0.5 - camZ)).rotateY((float) Math.toRadians(-facing.toYRot()));
    }
}
