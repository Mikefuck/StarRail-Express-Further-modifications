package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.client.mixin.FrustumAccessor;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayBoardPlacement;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayMapViewport;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * World renderer for settlement summary boards: a wooden diorama tray one block above each loaded
 * board, the shared miniature of the last match's map, synchronised avatars, trails and event nodes,
 * and the honors screen.
 *
 * <p>Boards are not block-entity rendered: the vanilla per-block-entity culling box would clip a
 * configurable diorama. Instead the loaded-board index from {@link ClientReplayBoard} is culled here
 * against the whole table, transformed tray and honors screen volume, faded between 48 and 64 blocks and
 * capped to the nearest few boards. Only the nearest board renews the data demand; the others reuse
 * the same shared view (every board shows the same last match).</p>
 */
public final class ReplayBoardRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayBoardRenderer.class.getSimpleName());
    private static final int MAX_BOARDS = 4;
    private static final float DEFAULT_TRAY_HALF_SIZE = 2.3f;

    private static MultiBufferSource.BufferSource buffers;
    private static final List<ByteBufferBuilder> OWNED = new ArrayList<>();
    private static final ReplayFanCutaway FAN_CUTAWAY = new ReplayFanCutaway();
    private static RenderType trayType;
    private static @Nullable ReplayBoardModel model;
    private static @Nullable LastMatchReplay modelSource;
    private static @Nullable ClientLevel observedLevel;
    private static boolean registered;
    private static boolean failed;

    private ReplayBoardRenderer() {}

    public static void init() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ReplayBoardRenderer::render);
        ReplayBoardLocalState.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ReplayBoardRenderer::reset));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != observedLevel) {
                observedLevel = client.level;
                ReplayDioramaMesh.reset();
                model = null;
                modelSource = null;
            }
            ReplayDioramaMesh.tickIdle();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            reset();
            releaseBuffers();
        });
    }

    /** Resource reload: atlas sprites and baked models changed, so the diorama mesh must be rebuilt. */
    public static void onResourceReload() {
        ReplayTrayPainter.invalidateSprites();
        ReplayDioramaMesh.reset();
    }

    /** Disconnect / session reset: drop GPU mesh, cached skins, models and local toggles. */
    public static void reset() {
        failed = false;
        ReplayDioramaMesh.reset();
        ReplayHeadSkins.clear();
        ReplayBoardLocalState.reset();
        model = null;
        modelSource = null;
    }

    private static void render(WorldRenderContext context) {
        if (failed || context == null || context.camera() == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        List<BlockPos> loaded = ClientReplayBoard.loadedBoards();
        if (loaded.isEmpty()) return;
        try {
            renderBoards(context, mc, level, loaded);
        } catch (RuntimeException failure) {
            // A renderer bug must never take the world pass down with it.
            failed = true;
            LOGGER.error("Settlement board rendering disabled after an unexpected failure", failure);
        }
    }

    private record Candidate(BlockPos pos, Direction facing, double distance, ReplayBoardSettings settings) {}

    private static float trayHalfSize(@Nullable ReplayBoardModel current) {
        if (current == null || current.layout == null) return DEFAULT_TRAY_HALF_SIZE;
        // Fit the longer map axis without changing the diorama's scale or aspect ratio.
        return Math.max(1.3f, Math.max(current.mapW, current.mapD) / 2 + 0.3f);
    }

    private static void renderBoards(WorldRenderContext context, Minecraft mc, ClientLevel level, List<BlockPos> loaded) {
        Vec3 cam = context.camera().getPosition();
        Frustum frustum = context.frustum();
        List<Candidate> candidates = new ArrayList<>();
        for (BlockPos pos : loaded) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(SettlementSummaryBoard.BLOCK)) continue;
            if (!(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;
            ReplayBoardSettings preview = ReplayBoardSettingsScreen.previewAt(pos);
            ReplayBoardSettings settings = preview == null ? marker.settings() : preview;
            double dx = pos.getX() + 0.5 - cam.x, dy = pos.getY() + 1.4 - cam.y, dz = pos.getZ() + 0.5 - cam.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > ReplayStagePlan.FADE_END) continue;
            Direction facing = state.getValue(SettlementSummaryBoard.FACING);
            Matrix4f worldTable = ReplayFrame.boardMatrix(pos, facing, 0, 0, 0);
            Vector3f center = worldTable.transformPosition(settings.offsetX(), settings.height(), settings.offsetZ(), new Vector3f());
            // Rotation can point the square tray's diagonal at the camera. Include all overlays and
            // the real table even when the floating display is moved far above/to one side of it.
            float reach = (trayHalfSize(model) + 0.6f) * settings.scale() * 1.415f;
            float visualHeight = Math.max(3.8f, model == null ? 0 : (model.mapH + 0.8f) * (float) (ReplayFocusPlan.MAX_ZOOM * 1.15)) * settings.scale();
            if (frustum != null && !((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                    Math.min(pos.getX(), center.x - reach), Math.min(pos.getY(), center.y - 0.7f * settings.scale()),
                    Math.min(pos.getZ(), center.z - reach), Math.max(pos.getX() + 1, center.x + reach),
                    Math.max(pos.getY() + 1, center.y + visualHeight), Math.max(pos.getZ() + 1, center.z + reach))) continue;
            candidates.add(new Candidate(pos, facing, distance, settings));
        }
        if (candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        if (candidates.size() > MAX_BOARDS) candidates = candidates.subList(0, MAX_BOARDS);

        ClientReplayBoard.View view = ClientReplayBoard.viewAt(candidates.getFirst().pos());
        LastMatchReplay replay = view.replay();
        ReplayTimeline timeline = view.timeline();
        if (replay == null || timeline == null) {
            if (!view.hasRound()) { model = null; modelSource = null; }
        } else if (replay != modelSource) {
            if (model != null && !model.replay.roundId().equals(replay.roundId())) ReplayBoardLocalState.reset();
            model = new ReplayBoardModel(replay, timeline);
            modelSource = replay;
        }
        // While a newer round's summary is still in flight, show the loading state rather than the stale model.
        ReplayBoardModel current = view.hasRound() && replay != null ? model : null;
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(true);
        long gameTime = level.getGameTime();
        ReplayTimeline.Frame frame = current == null ? null
                : current.timeline.at(ReplayBoardLocalState.tick(gameTime), ReplayBoardLocalState.partial(partial));
        double anim = (gameTime + partial) / 20.0;

        SceneMeshSet mesh = null;
        if (current != null && current.layout != null && view.asset() != null) {
            ReplayDioramaMesh.update(view.asset(), view.presentSections(), view.totalSections());
            if (ReplayDioramaMesh.matches(view.asset())) mesh = ReplayDioramaMesh.current();
        }

        float halfSize = trayHalfSize(current);

        MultiBufferSource.BufferSource source = buffers();
        ReplayFrame f = new ReplayFrame(mc, level, context.camera(), source, trayType, anim, current, frame,
                view.hasRound(), view.presentSections(), view.totalSections());
        List<ReplayBoardSite> sites = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            float fade = (float) ReplayStagePlan.distanceFade(c.distance());
            if (fade <= 0.01f) continue;
            int raw = LevelRenderer.getLightColor(level, c.pos().above());
            int light = LightTexture.pack(Math.max(7, LightTexture.block(raw)), LightTexture.sky(raw));
            Matrix4f table = ReplayFrame.boardMatrix(c.pos(), c.facing(), cam.x, cam.y, cam.z);
            Matrix4f display = ReplayBoardPlacement.displayMatrix(table, c.settings(), ReplayBoardSite.MAP_BASE);
            sites.add(new ReplayBoardSite(c.pos(), c.facing(), c.distance(), display, table, c.settings().scale(), halfSize, halfSize, fade,
                    c.distance() < ReplayStagePlan.DETAIL_DISTANCE, light));
        }
        if (sites.isEmpty()) return;

        try (var guard = ClientRenderState.capture()) {
            // 1. textured tray and its plaque body
            for (ReplayBoardSite s : sites) ReplayTrayPainter.tray(f, s);
            source.endBatch(trayType);
            // 2. the diorama map itself (shared VBO, one model matrix per board)
            if (mesh != null && f.stage != null) {
                for (ReplayBoardSite s : sites) drawMap(context, f, s, mesh);
            }
            // 3. collect fills / glow / deferred models and text
            for (ReplayBoardSite s : sites) {
                ReplayTrayPainter.lights(f, s);
                ReplayTrayPainter.plaque(f, s);
                if (current == null || current.layout == null) {
                    ReplayTrayPainter.idle(f, s);
                    continue;
                }
                float sink = f.stage == null ? 0 : (float) f.stage.mapSink() * 0.2f;
                Matrix4f d = f.focus.apply(new Matrix4f(s.board()).translate(0, ReplayBoardSite.MAP_BASE - sink, 0));
                try (var mapScope = f.mapScope(s)) {
                    if (mesh == null) loadingOutline(f, s, d, current);
                    ReplayTrayPainter.overlays(f, s, d, sink);
                    ReplayActorPainter.paint(f, s, d);
                    ReplayEventPainter.paint(f, s, d);
                }
                ReplayEventPainter.briefing(f, s);
                ReplayHonorsPainter.paint(f, s);
            }
            source.endBatch(ReplayDraw.FILL);
            f.flushModels();
            source.endLastBatch();
            source.endBatch(ReplayDraw.GLOW);
            f.flushTexts();
            source.endBatch();
        }
    }

    private static void loadingOutline(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel current) {
        double pulse = 0.45 + 0.35 * Math.sin(f.anim * 3.2);
        float w = current.mapW / 2, dd = current.mapD / 2, h = Math.max(0.1f, current.mapH);
        ReplayDraw.wireBox(f.glow(), d, -w, 0, -dd, w, h, dd, 0.008f, ReplayPalette.alpha(ReplayPalette.BRASS, pulse * s.fade()));
        double sweep = (f.anim * 0.4) % 1.0;
        float x = -w + (float) (2 * w * sweep);
        int c = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.35 * s.fade());
        ReplayDraw.quad(f.glow(), d, x, 0.004f, -dd, x, 0.004f, dd, x, h, dd, x, h, -dd, c, c,
                ReplayPalette.fade(c, 0), ReplayPalette.fade(c, 0));
    }

    private static void drawMap(WorldRenderContext context, ReplayFrame f, ReplayBoardSite s, SceneMeshSet mesh) {
        ReplayBoardModel current = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (current == null || current.layout == null || stage == null || stage.scan() <= 0.001) return;
        float sink = (float) stage.mapSink() * 0.2f;
        var src = current.source;
        Matrix4f sourceToTray = f.focus.apply(new Matrix4f())
                .rotateY(current.rotated ? (float) Math.toRadians(90) : 0f)
                .scale(current.scale)
                .translate(-src.sizeX() * 0.5f, 0, -src.sizeZ() * 0.5f);
        Matrix4f model = new Matrix4f(s.board()).translate(0, ReplayBoardSite.MAP_BASE - sink, 0).mul(sourceToTray);
        var bounds = ReplayMapViewport.sourceBounds(sourceToTray, s.hw() - ReplayBoardSite.FRAME - 0.04f,
                s.hd() - ReplayBoardSite.FRAME - 0.04f);
        Matrix4f modelView = new Matrix4f(context.positionMatrix()).mul(model);
        Matrix4f projection = context.projectionMatrix();
        double dim = (0.55 + 0.45 * s.fade()) * (1 - stage.mapSink() * 0.6);
        float r = (float) dim, g = (float) dim, b = (float) dim;
        if (f.blackout) {
            double pulse = 0.5 + 0.5 * Math.sin(f.anim * 5.2);
            r *= (float) (0.5 + 0.08 * pulse);
            g *= 0.34f;
            b *= 0.38f;
        }
        float[] previous = RenderSystem.getShaderColor().clone();
        boolean clip = stage.scan() < 0.999;
        int axisLength = current.rotated ? src.sizeZ() : src.sizeX();
        float threshold = (float) (stage.scan() * (axisLength + 1.0)) - 0.5f;
        updateFans(f, current, model);
        // The tray already has a local fill light. Give the train the same minimum so
        // the lobby's night lightmap cannot leave the train dark on a lit tray.
        try (var scope = SceneShaders.model(model, s.light());
             var boundsScope = SceneShaders.bounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
             var fanScope = SceneShaders.fans(FAN_CUTAWAY);
             var clipScope = clip ? (current.rotated ? SceneShaders.clip(0, 0, 1, threshold) : SceneShaders.clip(1, 0, 0, threshold))
                     : SceneShaders.clip(0, 0, 0, 1)) {
            RenderSystem.setShaderColor(r, g, b, 1f);
            opaque(mesh, modelView, projection);
            for (SceneMeshSet.Batch batch : mesh.batches()) opaque(batch.mesh(), modelView, projection);
            mesh.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projection, RenderType.translucent());
            mesh.renderCustomTranslucentBatches(modelView, projection);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }

    private static void updateFans(ReplayFrame f, ReplayBoardModel current, Matrix4f model) {
        FAN_CUTAWAY.clear();
        if (ReplayBoardLocalState.fullBody() || f.stage == null || f.stage.actors() <= 0.01) return;
        // Use a direction (w=0), not a position: offsets, table rotation and display size must
        // not reverse the opening. A quarter-turn source map is accounted for by this matrix too.
        FAN_CUTAWAY.faceViewer(model, new Vector3f(f.camera.getLookVector()), current.source);
        // Avatars have a fixed diorama size, independent of map dimensions. Keep the rounded
        // apex wide enough for the head and hat even on a very long train.
        double radius = Math.max(1.25, ReplayActorPainter.HEAD * 0.75 / current.scale);
        for (ReplayBoardModel.Actor actor : current.actors) {
            FAN_CUTAWAY.add(actor.track.sample(f.replayTick), current.layout.origin(), current.source, radius);
        }
    }

    private static void opaque(SceneMeshSet mesh, Matrix4f modelView, Matrix4f projection) {
        mesh.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projection, RenderType.solid());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projection, RenderType.cutoutMipped());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projection, RenderType.cutout());
        mesh.renderCustomOpaqueBatches(modelView, projection);
    }

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            trayType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
            SequencedMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            fixed.put(trayType, own(1 << 17));
            fixed.put(ReplayDraw.FILL, own(1 << 16));
            fixed.put(ReplayDraw.GLOW, own(1 << 18));
            buffers = MultiBufferSource.immediateWithBuffers(fixed, own(1 << 17));
        }
        return buffers;
    }

    private static ByteBufferBuilder own(int size) {
        ByteBufferBuilder builder = new ByteBufferBuilder(size);
        OWNED.add(builder);
        return builder;
    }

    private static void releaseBuffers() {
        buffers = null;
        for (ByteBufferBuilder builder : OWNED) builder.close();
        OWNED.clear();
    }
}
