package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.habitrain.core.scene.client.replay.ReplayBoardSite.*;

/**
 * The wooden, brass-trimmed diorama tray one block above the marker, its Art Deco flip-board
 * plaque, the faint holographic support beams and the stage-wide overlays (scan-in, settlement
 * wave, blackout glow). Also draws the idle "waiting for departure" wire train.
 */
final class ReplayTrayPainter {
    private ReplayTrayPainter() {}

    private record Material(TextureAtlasSprite sprite, int tint) {}

    private static Material herringbone, mahogany, ebony, ebonyWall, brass, corner;

    static void invalidateSprites() {
        herringbone = mahogany = ebony = ebonyWall = brass = corner = null;
    }

    private static Material material(String wathe, String fallback, int fallbackTint) {
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite sprite = atlas.apply(ResourceLocation.fromNamespaceAndPath("wathe", wathe));
        if (!MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) return new Material(sprite, 0xFFFFFFFF);
        return new Material(atlas.apply(ResourceLocation.withDefaultNamespace(fallback)), fallbackTint);
    }

    private static void ensureSprites() {
        if (herringbone != null) return;
        herringbone = material("block/mahogany_herringbone", "block/dark_oak_planks", 0xFFC27A5E);
        mahogany = material("block/mahogany_planks", "block/dark_oak_planks", 0xFFB06A52);
        ebony = material("block/ebony_planks", "block/stripped_dark_oak_log", 0xFF5E4A44);
        ebonyWall = material("block/ebony_cabinet_wall", "block/stripped_dark_oak_log", 0xFF4E3C38);
        brass = material("block/gold_ledge", "block/gold_block", 0xFFE6C27A);
        corner = material("block/gold_ornament_corner", "block/gold_block", 0xFFF0CC84);
    }

    // ------------------------------------------------------------------ tray (textured, opaque)

    static void tray(ReplayFrame f, ReplayBoardSite s) {
        ensureSprites();
        VertexConsumer vc = f.tray();
        PoseStack stack = new PoseStack();
        stack.mulPose(s.board());
        PoseStack.Pose pose = stack.last();
        float hw = s.hw(), hd = s.hd(), ft = FRAME;
        int l = s.light();
        // floor
        box(vc, pose, -hw + ft * 0.5f, TRAY_BOTTOM + 0.03f, -hd + ft * 0.5f, hw - ft * 0.5f, BASE_TOP, hd - ft * 0.5f,
                mahogany, herringbone, l, 0.5f);
        // ebony frame
        box(vc, pose, -hw, TRAY_BOTTOM, hd - ft, hw, FRAME_TOP, hd, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, -hw, TRAY_BOTTOM, -hd, hw, FRAME_TOP, -hd + ft, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, -hw, TRAY_BOTTOM, -hd + ft, -hw + ft, FRAME_TOP, hd - ft, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, hw - ft, TRAY_BOTTOM, -hd + ft, hw, FRAME_TOP, hd - ft, ebonyWall, ebony, l, 0.5f);
        // mahogany bevel lip and brass ledge on the inner edge
        float lip = 0.035f, ly = FRAME_TOP - 0.035f;
        box(vc, pose, -hw + ft, ly, hd - ft - lip, hw - ft, FRAME_TOP - 0.01f, hd - ft, mahogany, mahogany, l, 0.5f);
        box(vc, pose, -hw + ft, ly, -hd + ft, hw - ft, FRAME_TOP - 0.01f, -hd + ft + lip, mahogany, mahogany, l, 0.5f);
        box(vc, pose, -hw + ft, ly, -hd + ft + lip, -hw + ft + lip, FRAME_TOP - 0.01f, hd - ft - lip, mahogany, mahogany, l, 0.5f);
        box(vc, pose, hw - ft - lip, ly, -hd + ft + lip, hw - ft, FRAME_TOP - 0.01f, hd - ft - lip, mahogany, mahogany, l, 0.5f);
        float le = 0.028f, top = FRAME_TOP + 0.012f;
        box(vc, pose, -hw + ft - le, FRAME_TOP, hd - ft - 0.004f, hw - ft + le, top, hd - ft + le, brass, brass, l, 0.5f);
        box(vc, pose, -hw + ft - le, FRAME_TOP, -hd + ft - le, hw - ft + le, top, -hd + ft + 0.004f, brass, brass, l, 0.5f);
        box(vc, pose, -hw + ft - le, FRAME_TOP, -hd + ft, -hw + ft + 0.004f, top, hd - ft, brass, brass, l, 0.5f);
        box(vc, pose, hw - ft - 0.004f, FRAME_TOP, -hd + ft, hw - ft + le, top, hd - ft, brass, brass, l, 0.5f);
        // brass corner caps with rivet bosses
        float cs = 0.25f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float cx = sx * (hw - cs * 0.5f + 0.02f), cz = sz * (hd - cs * 0.5f + 0.02f);
                box(vc, pose, cx - cs / 2, FRAME_TOP - 0.02f, cz - cs / 2, cx + cs / 2, FRAME_TOP + 0.035f, cz + cs / 2,
                        corner, corner, l, 0.25f);
                box(vc, pose, cx - 0.03f, FRAME_TOP + 0.035f, cz - 0.03f, cx + 0.03f, FRAME_TOP + 0.06f, cz + 0.03f,
                        brass, brass, l, 0.25f);
            }
        }
        if (s.detail()) {
            // rivets along the frame
            float ry = FRAME_TOP + 0.016f;
            for (float x = -hw + 0.45f; x < hw - 0.3f; x += 0.42f) {
                rivet(vc, pose, x, ry, hd - ft * 0.5f, l);
                rivet(vc, pose, x, ry, -hd + ft * 0.5f, l);
            }
            for (float z = -hd + 0.45f; z < hd - 0.3f; z += 0.42f) {
                rivet(vc, pose, -hw + ft * 0.5f, ry, z, l);
                rivet(vc, pose, hw - ft * 0.5f, ry, z, l);
            }
        }
        // undercarriage: sleepers and a miniature double track
        for (float x = -hw + 0.3f; x <= hw - 0.25f; x += 0.34f) {
            box(vc, pose, x - 0.05f, TRAY_BOTTOM - 0.075f, -hd * 0.62f, x + 0.05f, TRAY_BOTTOM - 0.03f, hd * 0.62f,
                    ebony, ebony, l, 0.5f);
        }
        for (int side = -1; side <= 1; side += 2) {
            float z = side * hd * 0.42f;
            box(vc, pose, -hw + 0.12f, TRAY_BOTTOM - 0.03f, z - 0.022f, hw - 0.12f, TRAY_BOTTOM, z + 0.022f,
                    brass, brass, l, 0.5f);
        }
        plaqueBody(f, s, vc);
    }

    private static void rivet(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int light) {
        box(vc, pose, x - 0.018f, y - 0.004f, z - 0.018f, x + 0.018f, y + 0.014f, z + 0.018f, brass, brass, light, 0.25f);
    }

    private static void box(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1,
                            Material side, Material top, int light, float tile) {
        // One tint per call: wathe textures are already coloured, fallbacks carry their own tint.
        ReplayDraw.box(vc, pose, x0, y0, z0, x1, y1, z1, side.sprite(), top.sprite(), top.tint(), light, tile);
    }

    // ------------------------------------------------------------------ plaque

    static float plaqueWidth(ReplayBoardSite s) { return Math.min(2.9f, Math.max(1.6f, s.hw() * 2 - 0.5f)); }
    static final float PLAQUE_H = 0.36f;

    static Matrix4f plaqueMatrix(ReplayBoardSite s) {
        return new Matrix4f(s.board()).translate(0, 0.14f, s.hd() + 0.035f).rotateX((float) Math.toRadians(-20));
    }

    private static void plaqueBody(ReplayFrame f, ReplayBoardSite s, VertexConsumer vc) {
        PoseStack stack = new PoseStack();
        stack.mulPose(plaqueMatrix(s));
        PoseStack.Pose pose = stack.last();
        float pw = plaqueWidth(s), ph = PLAQUE_H;
        box(vc, pose, -pw / 2 - 0.035f, -ph / 2 - 0.035f, -0.045f, pw / 2 + 0.035f, ph / 2 + 0.035f, -0.02f,
                ebonyWall, ebony, s.light(), 0.5f);
        box(vc, pose, -pw / 2, -ph / 2, -0.02f, pw / 2, ph / 2, 0f, brass, brass, s.light(), 0.5f);
        // fan ornaments at both ends
        for (int side = -1; side <= 1; side += 2) {
            float x = side * (pw / 2 + 0.06f);
            box(vc, pose, x - 0.05f, -0.06f, -0.04f, x + 0.05f, 0.06f, -0.005f, corner, corner, s.light(), 0.25f);
        }
    }

    private static final char[] LAST_CLOCK = new char[16];
    private static final long[] CLOCK_CHANGED = new long[16];

    static void plaque(ReplayFrame f, ReplayBoardSite s) {
        Matrix4f m = plaqueMatrix(s);
        float pw = plaqueWidth(s), ph = PLAQUE_H;
        float alpha = s.fade();
        VertexConsumer fill = f.fill();
        // engraved night inset
        ReplayDraw.rectXY(fill, m, -pw / 2 + 0.035f, -ph / 2 + 0.035f, pw / 2 - 0.035f, ph / 2 - 0.035f, 0.002f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * alpha), ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * alpha));
        ReplayDraw.rectXY(fill, m, -pw / 2 + 0.05f, 0.005f, pw / 2 - 0.05f, 0.009f, 0.003f,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.8 * alpha), ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.8 * alpha));
        float left = -pw / 2 + 0.07f, rightEdge = pw / 2 - 0.07f, z = 0.006f;
        var model = f.model;
        if (model == null) {
            Component line = f.hasRound ? ReplayBoardTexts.tr("loading") : ReplayBoardTexts.tr("idle");
            f.text(m, line, 0, 0.075f, z, 0.0052f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, alpha), 0, false, pw - 0.16f);
            int dots = (int) (f.anim * 3) % 4;
            Component sub = ReplayBoardTexts.tr("brand").copy().append(".".repeat(f.hasRound ? dots : 0));
            f.text(m, sub, 0, -0.07f, z, 0.0042f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, alpha), 0, false, pw - 0.16f);
            return;
        }
        var stage = f.stage;
        double flip = stage == null ? 1 : stage.plaqueFlip();
        float flipScale = (float) Math.max(0.05, flip);
        Matrix4f titleM = new Matrix4f(m).translate(0, 0.075f, 0).scale(1, flipScale, 1);
        f.text(titleM, Component.literal(model.replay.mapName()), left, 0, z, 0.0054f,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, alpha * flip), -1, false, pw * 0.58f);
        f.text(titleM, model.modeLabel, rightEdge, 0, z, 0.0042f,
                ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, alpha * flip), 1, false, pw * 0.36f);
        // flip-board clock "mm:ss / mm:ss"
        String clock = ReplayStagePlan.clock(f.replayTick) + "/" + ReplayStagePlan.clock(model.replay.durationTicks());
        long now = System.nanoTime();
        float tileW = 0.046f, tileH = 0.072f, gap = 0.006f, cy = -0.065f;
        float x = left + tileW / 2;
        VertexConsumer glow = f.glow();
        for (int i = 0; i < clock.length() && i < LAST_CLOCK.length; i++) {
            char c = clock.charAt(i);
            if (LAST_CLOCK[i] != c) {
                LAST_CLOCK[i] = c;
                CLOCK_CHANGED[i] = now;
            }
            double p = Math.min(1, (now - CLOCK_CHANGED[i]) / 160_000_000.0);
            float squash = (float) Math.max(0.08, Math.abs(Math.cos(p * Math.PI)));
            boolean digit = Character.isDigit(c);
            float w = digit ? tileW : tileW * 0.55f;
            float cx = x + (w - tileW) / 2;
            if (digit) {
                ReplayDraw.rectXY(fill, m, cx - w / 2, cy - tileH / 2, cx + w / 2, cy, 0.004f,
                        ReplayPalette.alpha(0xFF0B0F1C, alpha), ReplayPalette.alpha(0xFF070A14, alpha));
                ReplayDraw.rectXY(fill, m, cx - w / 2, cy, cx + w / 2, cy + tileH / 2, 0.004f,
                        ReplayPalette.alpha(0xFF1A2440, alpha), ReplayPalette.alpha(0xFF101830, alpha));
                ReplayDraw.rectXY(fill, m, cx - w / 2, cy - 0.0015f, cx + w / 2, cy + 0.0015f, 0.005f,
                        ReplayPalette.alpha(0xFF000000, 0.9 * alpha), ReplayPalette.alpha(0xFF000000, 0.9 * alpha));
                if (p < 1) {
                    ReplayDraw.rectXY(glow, m, cx - w / 2, cy - 0.003f, cx + w / 2, cy + 0.003f, 0.006f,
                            ReplayPalette.alpha(ReplayPalette.LAMP, (1 - p) * 0.8 * alpha), ReplayPalette.alpha(ReplayPalette.LAMP, (1 - p) * 0.8 * alpha));
                }
            }
            Matrix4f cm = new Matrix4f(m).translate(cx, cy, 0).scale(1, squash, 1);
            f.text(cm, Component.literal(String.valueOf(c)), 0, 0, z, 0.0058f,
                    ReplayPalette.alpha(digit ? ReplayPalette.BRASS_LIGHT : ReplayPalette.TEXT_MUTED, alpha), 0, false, 0);
            x += w + gap;
        }
        // phase label
        Component phase = phaseLabel(f);
        f.text(m, phase, rightEdge, cy, z, 0.0046f, ReplayPalette.alpha(f.frame != null && f.frame.slowMotion()
                ? ReplayPalette.RUBY : ReplayPalette.LAMP, alpha), 1, false, pw * 0.4f);
        // progress rail
        double progress = f.frame == null ? 0 : f.frame.phase() == ReplayTimeline.Phase.BUILD ? 0
                : model.replay.durationTicks() <= 0 ? 1 : Mth.clamp(f.replayTick / model.replay.durationTicks(), 0, 1);
        float barY = -ph / 2 + 0.05f, bx0 = left, bx1 = rightEdge;
        ReplayDraw.rectXY(fill, m, bx0, barY - 0.004f, bx1, barY + 0.004f, 0.004f,
                ReplayPalette.alpha(0xFF050814, alpha), ReplayPalette.alpha(0xFF050814, alpha));
        float px = bx0 + (float) ((bx1 - bx0) * progress);
        ReplayDraw.rectXY(glow, m, bx0, barY - 0.003f, px, barY + 0.003f, 0.005f,
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.85 * alpha), ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.85 * alpha));
        if (f.totalSections > 0 && f.presentSections < f.totalSections) {
            Component sync = ReplayBoardTexts.tr("map_progress", f.presentSections, f.totalSections);
            f.text(m, sync, 0, barY + 0.022f, z, 0.0032f, ReplayPalette.alpha(ReplayPalette.PASSENGER, 0.9 * alpha), 0, false, 0);
        }
    }

    private static Component phaseLabel(ReplayFrame f) {
        if (ReplayBoardLocalState.paused()) return ReplayBoardTexts.tr("phase.paused");
        if (f.frame == null) return Component.empty();
        return switch (f.frame.phase()) {
            case BUILD -> ReplayBoardTexts.tr("phase.build");
            case PLAY -> ReplayBoardTexts.tr(f.frame.slowMotion() ? "phase.slow" : "phase.play");
            case REVIEW -> ReplayBoardTexts.tr("phase.review");
            case HONORS -> ReplayBoardTexts.tr("phase.honors");
        };
    }

    // ------------------------------------------------------------------ light work

    static void lights(ReplayFrame f, ReplayBoardSite s) {
        double glowLevel = f.stage == null ? 0.75 : f.stage.trayGlow();
        double a = s.fade() * glowLevel;
        if (a <= 0.01) return;
        VertexConsumer glow = f.glow();
        Matrix4f m = s.board();
        float hw = s.hw() - FRAME, hd = s.hd() - FRAME, y = FRAME_TOP + 0.02f;
        double flicker = 0.92 + 0.08 * Math.sin(f.anim * 7.3) * Math.sin(f.anim * 2.1);
        int lamp = ReplayPalette.alpha(ReplayPalette.LAMP, 0.55 * a * flicker);
        ReplayDraw.segment(glow, m, -hw, y, hd, hw, y, hd, 0.022f, lamp, lamp);
        ReplayDraw.segment(glow, m, -hw, y, -hd, hw, y, -hd, 0.022f, lamp, lamp);
        ReplayDraw.segment(glow, m, -hw, y, -hd, -hw, y, hd, 0.022f, lamp, lamp);
        ReplayDraw.segment(glow, m, hw, y, -hd, hw, y, hd, 0.022f, lamp, lamp);
        // warm wash just inside the frame
        int wash = ReplayPalette.alpha(ReplayPalette.LAMP, 0.10 * a), clear = ReplayPalette.alpha(ReplayPalette.LAMP, 0);
        float fy = BASE_TOP + 0.003f, in = 0.22f;
        ReplayDraw.quad(glow, m, -hw, fy, hd, hw, fy, hd, hw - in, fy, hd - in, -hw + in, fy, hd - in, wash, wash, clear, clear);
        ReplayDraw.quad(glow, m, hw, fy, -hd, -hw, fy, -hd, -hw + in, fy, -hd + in, hw - in, fy, -hd + in, wash, wash, clear, clear);
        if (s.detail()) {
            for (float x = -hw + 0.25f; x < hw; x += 0.5f) {
                Vector3f p = m.transformPosition(x, y + 0.01f, hd, new Vector3f());
                ReplayDraw.billboard(glow, p, f.right, f.up, 0.018f, 0.018f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.7 * a));
            }
        }
        // Holographic support beams keep their feet on the real wooden tabletop, even when the
        // display is scaled, rotated or offset. Endpoints are already in camera-relative space.
        double beam = s.fade() * (0.6 + 0.4 * glowLevel);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float x0 = sx * 0.36f, z0 = sz * 0.36f;
                float x1 = sx * (s.hw() - 0.24f), z1 = sz * (s.hd() - 0.24f), y1 = TRAY_BOTTOM - 0.075f;
                Vector3f start = s.table().transformPosition(x0, 0.005f, z0, new Vector3f());
                Vector3f end = m.transformPosition(x1, y1, z1, new Vector3f());
                ReplayDraw.segment(glow, start, end, 0.016f,
                        ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.30 * beam), ReplayPalette.alpha(ReplayPalette.LAMP, 0.08 * beam));
                double t = (f.anim * 0.45 + (sx + 2) * 0.17 + (sz + 2) * 0.31) % 1.0;
                Vector3f dot = new Vector3f(start).lerp(end, (float) t);
                ReplayDraw.billboard(glow, dot, f.right, f.up, 0.02f, 0.02f,
                        ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.45 * beam * Math.sin(t * Math.PI)));
            }
        }
    }

    /** Scan-in plane + wireframe, settlement wave and blackout glow over the diorama. */
    static void overlays(ReplayFrame f, ReplayBoardSite s, Matrix4f d, float sink) {
        var model = f.model;
        var stage = f.stage;
        if (model == null || stage == null) return;
        VertexConsumer glow = f.glow();
        float w = model.mapW / 2 + 0.04f, dd = model.mapD / 2 + 0.04f, h = Math.max(0.12f, model.mapH + 0.04f);
        double a = s.fade();
        if (stage.scan() < 1 && stage.scan() > 0) {
            float x = -w + (float) (2 * w * stage.scan());
            int gold = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.55 * a), clear = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0);
            ReplayDraw.quad(glow, d, x, 0, -dd, x, 0, dd, x, h, dd, x, h, -dd, gold, gold, clear, clear);
            ReplayDraw.segment(glow, d, x, 0.004f, -dd, x, 0.004f, dd, 0.02f, gold, gold);
            ReplayDraw.wireBox(glow, d, -w, 0, -dd, w, h, dd, 0.008f,
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.55 * a * (1 - stage.scan() * 0.5)));
        }
        if (stage.reviewWave() >= 0) {
            float x = -w + (float) (2 * w * stage.reviewWave());
            int core = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.5 * a), clear = ReplayPalette.alpha(ReplayPalette.BRASS, 0);
            ReplayDraw.quad(glow, d, x, 0, -dd, x, 0, dd, x, h * 1.2f, dd, x, h * 1.2f, -dd, core, core, clear, clear);
            int trail = ReplayPalette.alpha(ReplayPalette.BRASS, 0.22 * a);
            ReplayDraw.quad(glow, d, x - 0.35f, 0.006f, -dd, x, 0.006f, -dd, x, 0.006f, dd, x - 0.35f, 0.006f, dd,
                    clear, trail, trail, clear);
        }
        if (f.blackout) {
            double pulse = 0.55 + 0.45 * Math.sin(f.anim * 5.2);
            int red = ReplayPalette.alpha(ReplayPalette.EMERGENCY, 0.16 * a * pulse);
            ReplayDraw.rectXZ(glow, d, -w, -dd, w, dd, 0.01f, red);
            ReplayDraw.wireBox(glow, d, -w, 0, -dd, w, h, dd, 0.006f, ReplayPalette.alpha(ReplayPalette.EMERGENCY, 0.35 * a * pulse));
        }
    }

    // ------------------------------------------------------------------ idle / loading

    private static float[] train;

    static void idle(ReplayFrame f, ReplayBoardSite s) {
        if (train == null) train = WireTrain.build();
        VertexConsumer glow = f.glow();
        float scale = Math.min(1f, (s.hw() * 2 - 0.6f) / 3.4f);
        float bob = (float) Math.sin(f.anim * 1.3) * 0.03f;
        Matrix4f m = new Matrix4f(s.board()).translate(0, FRAME_TOP + 0.55f + bob, 0).scale(scale);
        double pulse = f.hasRound ? 0.55 + 0.45 * Math.sin(f.anim * 4) : 1;
        int gold = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.75 * s.fade() * pulse);
        int dim = ReplayPalette.alpha(ReplayPalette.BRASS, 0.25 * s.fade() * pulse);
        Vector3f a = new Vector3f(), b = new Vector3f();
        for (int i = 0; i + 5 < train.length; i += 6) {
            m.transformPosition(train[i], train[i + 1], train[i + 2], a);
            m.transformPosition(train[i + 3], train[i + 4], train[i + 5], b);
            ReplayDraw.segment(glow, a, b, 0.024f * scale, dim, dim);
            ReplayDraw.segment(glow, a, b, 0.009f * scale, gold, gold);
        }
        Matrix4f sign = new Matrix4f(s.board()).translate(0, FRAME_TOP + 1.35f, 0);
        Component text = f.hasRound ? ReplayBoardTexts.tr("loading") : ReplayBoardTexts.tr("idle");
        float width = Minecraft.getInstance().font.width(text) * 0.008f + 0.3f;
        VertexConsumer fill = f.fill();
        ReplayDraw.rectXY(fill, sign, -width / 2, -0.09f, width / 2, 0.09f, 0, ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.85 * s.fade()),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.85 * s.fade()));
        ReplayDraw.segment(glow, sign, -width / 2, 0.09f, 0.002f, width / 2, 0.09f, 0.002f, 0.008f, gold, gold);
        ReplayDraw.segment(glow, sign, -width / 2, -0.09f, 0.002f, width / 2, -0.09f, 0.002f, 0.008f, gold, gold);
        f.text(sign, text, 0, 0, 0.004f, 0.008f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, s.fade()), 0, true, 0);
    }

    /** Golden wireframe steam train (segments, x0 y0 z0 x1 y1 z1), centred on the origin. */
    static final class WireTrain {
        private float[] seg = new float[6 * 256];
        private int n;

        static float[] build() {
            WireTrain t = new WireTrain();
            float off = -1.6f;
            // locomotive frame, cab, boiler, chimney, dome, wheels, cowcatcher
            t.box(off, 0.08f, -0.17f, off + 1.25f, 0.15f, 0.17f);
            t.box(off, 0.15f, -0.19f, off + 0.38f, 0.62f, 0.19f);
            t.box(off - 0.04f, 0.62f, -0.22f, off + 0.42f, 0.66f, 0.22f);
            t.cylinderX(off + 0.38f, off + 1.18f, 0.33f, 0, 0.16f, 14, 6);
            t.circleX(off + 0.62f, 0.33f, 0, 0.165f, 14);
            t.circleX(off + 0.9f, 0.33f, 0, 0.165f, 14);
            t.cylinderY(0.48f, 0.68f, off + 1.02f, 0, 0.05f, 8);
            t.cylinderY(0.47f, 0.56f, off + 0.72f, 0, 0.06f, 8);
            for (float wx : new float[]{0.22f, 0.58f, 0.92f}) {
                t.circleZ(off + wx, 0.12f, 0.19f, 0.11f, 12);
                t.circleZ(off + wx, 0.12f, -0.19f, 0.11f, 12);
            }
            t.line(off + 0.22f, 0.12f, 0.2f, off + 0.92f, 0.12f, 0.2f);
            t.line(off + 1.25f, 0.08f, -0.17f, off + 1.42f, 0.02f, 0);
            t.line(off + 1.25f, 0.08f, 0.17f, off + 1.42f, 0.02f, 0);
            t.line(off + 1.25f, 0.15f, 0, off + 1.42f, 0.02f, 0);
            // two coaches behind the locomotive
            for (int c = 0; c < 2; c++) {
                float x0 = off - 0.08f - 0.98f * (c + 1), x1 = x0 + 0.9f;
                t.box(x0, 0.1f, -0.18f, x1, 0.52f, 0.18f);
                t.line(x0 - 0.02f, 0.56f, 0, x1 + 0.02f, 0.56f, 0);
                t.line(x0, 0.52f, -0.18f, x0 - 0.02f, 0.56f, 0);
                t.line(x1, 0.52f, -0.18f, x1 + 0.02f, 0.56f, 0);
                t.line(x0, 0.52f, 0.18f, x0 - 0.02f, 0.56f, 0);
                t.line(x1, 0.52f, 0.18f, x1 + 0.02f, 0.56f, 0);
                for (int wdw = 0; wdw < 4; wdw++) {
                    float wx = x0 + 0.08f + wdw * 0.2f;
                    t.rectZ(0.18f, wx, 0.27f, wx + 0.13f, 0.43f);
                    t.rectZ(-0.18f, wx, 0.27f, wx + 0.13f, 0.43f);
                }
                t.circleZ(x0 + 0.18f, 0.08f, 0.18f, 0.07f, 10);
                t.circleZ(x1 - 0.18f, 0.08f, 0.18f, 0.07f, 10);
                t.circleZ(x0 + 0.18f, 0.08f, -0.18f, 0.07f, 10);
                t.circleZ(x1 - 0.18f, 0.08f, -0.18f, 0.07f, 10);
                t.line(x1, 0.2f, 0, x1 + 0.08f, 0.2f, 0);
            }
            // rails
            t.line(-3.3f, 0.005f, 0.19f, 1.7f, 0.005f, 0.19f);
            t.line(-3.3f, 0.005f, -0.19f, 1.7f, 0.005f, -0.19f);
            float[] out = java.util.Arrays.copyOf(t.seg, t.n * 6);
            // centre on x
            for (int i = 0; i < out.length; i += 3) out[i] += 0.8f;
            return out;
        }

        void line(float x0, float y0, float z0, float x1, float y1, float z1) {
            if ((n + 1) * 6 > seg.length) seg = java.util.Arrays.copyOf(seg, seg.length * 2);
            int o = n * 6;
            seg[o] = x0; seg[o + 1] = y0; seg[o + 2] = z0; seg[o + 3] = x1; seg[o + 4] = y1; seg[o + 5] = z1;
            n++;
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1) {
            line(x0, y0, z0, x1, y0, z0); line(x1, y0, z0, x1, y0, z1); line(x1, y0, z1, x0, y0, z1); line(x0, y0, z1, x0, y0, z0);
            line(x0, y1, z0, x1, y1, z0); line(x1, y1, z0, x1, y1, z1); line(x1, y1, z1, x0, y1, z1); line(x0, y1, z1, x0, y1, z0);
            line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x1, y0, z1, x1, y1, z1); line(x0, y0, z1, x0, y1, z1);
        }

        void rectZ(float z, float x0, float y0, float x1, float y1) {
            line(x0, y0, z, x1, y0, z); line(x1, y0, z, x1, y1, z); line(x1, y1, z, x0, y1, z); line(x0, y1, z, x0, y0, z);
        }

        void circleX(float cx, float cy, float cz, float r, int sides) {
            for (int i = 0; i < sides; i++) {
                float a0 = Mth.TWO_PI * i / sides, a1 = Mth.TWO_PI * (i + 1) / sides;
                line(cx, cy + Mth.cos(a0) * r, cz + Mth.sin(a0) * r, cx, cy + Mth.cos(a1) * r, cz + Mth.sin(a1) * r);
            }
        }

        void circleZ(float cx, float cy, float cz, float r, int sides) {
            for (int i = 0; i < sides; i++) {
                float a0 = Mth.TWO_PI * i / sides, a1 = Mth.TWO_PI * (i + 1) / sides;
                line(cx + Mth.cos(a0) * r, cy + Mth.sin(a0) * r, cz, cx + Mth.cos(a1) * r, cy + Mth.sin(a1) * r, cz);
            }
        }

        void cylinderX(float x0, float x1, float cy, float cz, float r, int sides, int generators) {
            circleX(x0, cy, cz, r, sides);
            circleX(x1, cy, cz, r, sides);
            for (int i = 0; i < generators; i++) {
                float a = Mth.TWO_PI * i / generators + 0.3f;
                line(x0, cy + Mth.cos(a) * r, cz + Mth.sin(a) * r, x1, cy + Mth.cos(a) * r, cz + Mth.sin(a) * r);
            }
        }

        void cylinderY(float y0, float y1, float cx, float cz, float r, int sides) {
            for (int i = 0; i < sides; i++) {
                float a0 = Mth.TWO_PI * i / sides, a1 = Mth.TWO_PI * (i + 1) / sides;
                line(cx + Mth.cos(a0) * r, y0, cz + Mth.sin(a0) * r, cx + Mth.cos(a1) * r, y0, cz + Mth.sin(a1) * r);
                line(cx + Mth.cos(a0) * r, y1, cz + Mth.sin(a0) * r, cx + Mth.cos(a1) * r, y1, cz + Mth.sin(a1) * r);
                if (i % 2 == 0) line(cx + Mth.cos(a0) * r, y0, cz + Mth.sin(a0) * r, cx + Mth.cos(a0) * r, y1, cz + Mth.sin(a0) * r);
            }
        }
    }
}
