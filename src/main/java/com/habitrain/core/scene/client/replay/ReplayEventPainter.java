package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/**
 * Spatial event nodes: kill pulses with floating 3D weapons and slash lines, ballistic beams with
 * muzzle stars and impact sparks, explosion / poison / shield / door / skill halos, plus the
 * briefing bar shown while a highlight is in slow motion. In the review and honors acts every node
 * is shown as a quiet static marker for an overview of the whole match.
 */
final class ReplayEventPainter {
    private ReplayEventPainter() {}

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (model == null || stage == null || f.frame == null || stage.actors() <= 0.01) return;
        double t = f.replayTick;
        double a = s.fade() * stage.actors();
        boolean play = f.frame.phase() == ReplayTimeline.Phase.PLAY;
        double emphasis = f.frame.slowMotion() ? 1.6 : 1.0;
        VertexConsumer glow = f.glow();
        for (ReplayBoardModel.EventMark e : model.events) {
            double age = t - e.node.tick();
            if (age < 0) continue;
            double window = window(e.node.kind(), model.rate);
            if (play && age <= window) {
                live(f, s, d, glow, e, age, window, a, emphasis);
            } else if (stage.history() > 0.01 || age > window) {
                marker(f, d, glow, e, a * (play ? 0.35 : stage.history()));
            }
        }
    }

    static double window(Kind kind, double rate) {
        double seconds = switch (kind) {
            case KILL -> 2.4;
            case GUNSHOT -> 0.7;
            case GRENADE_EXPLOSION, BOMB_DETONATE -> 1.6;
            case POISON, SHIELD_BROKEN, PLAYER_REVIVAL -> 1.3;
            default -> 1.0;
        };
        return ReplayStagePlan.effectWindow(seconds, rate);
    }

    private static void live(ReplayFrame f, ReplayBoardSite s, Matrix4f d, VertexConsumer glow,
                             ReplayBoardModel.EventMark e, double age, double window, double a, double emphasis) {
        double k = age / window;
        double fadeOut = 1 - ReplayStagePlan.easeOutCubic(k);
        float x = e.tx, y = e.ty + 0.01f, z = e.tz;
        switch (e.node.kind()) {
            case KILL -> {
                for (int ring = 0; ring < 2; ring++) {
                    double rk = Math.min(1, k * 1.6 - ring * 0.25);
                    if (rk <= 0) continue;
                    float r = (float) ((0.05 + 0.32 * ReplayStagePlan.easeOutCubic(rk)) * emphasis);
                    ReplayDraw.ring(glow, d, x, y, z, r, 0.022f, 28, ReplayPalette.alpha(ReplayPalette.RUBY, (1 - rk) * 0.9 * a),
                            ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                }
                ReplayDraw.column(glow, d, x, y, z, (float) (0.5 * emphasis), 0.05f,
                        ReplayPalette.alpha(ReplayPalette.RUBY, 0.55 * fadeOut * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                if (e.hasFrom && k < 0.45) {
                    double sk = 1 - k / 0.45;
                    ReplayDraw.segment(glow, d, e.fx, e.fy + 0.08f, e.fz, x, y + 0.08f, z, 0.03f,
                            ReplayPalette.alpha(0xFFFFFFFF, 0.2 * sk * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0.95 * sk * a));
                }
                weapon(f, d, e, x, y + 0.26f, z, (float) (0.15 * emphasis), fadeOut * a);
            }
            case GUNSHOT -> {
                boolean hit = e.has(HIT);
                int core = e.has(BACKFIRE) ? ReplayPalette.EXPLOSION : 0xFFFFFFFF;
                int halo = e.has(BACKFIRE) ? ReplayPalette.EXPLOSION : ReplayPalette.BRASS_LIGHT;
                double beam = k < 0.65 ? 1 - k / 0.65 : 0;
                if (beam > 0) {
                    ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.045f,
                            ReplayPalette.alpha(halo, 0.35 * beam * a), ReplayPalette.alpha(halo, 0.15 * beam * a));
                    ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.014f,
                            ReplayPalette.alpha(core, 0.95 * beam * a), ReplayPalette.alpha(core, 0.6 * beam * a));
                }
                Vector3f muzzle = d.transformPosition(e.fx, e.fy, e.fz, new Vector3f());
                ReplayDraw.star(glow, muzzle, f.right, f.up, (float) (0.09 * emphasis * fadeOut), 8, (float) (age * 0.3),
                        ReplayPalette.alpha(core, fadeOut * a), ReplayPalette.alpha(halo, 0));
                Vector3f impact = d.transformPosition(e.tx, e.ty, e.tz, new Vector3f());
                if (hit) {
                    ReplayDraw.ring(glow, d, e.tx, e.ty, e.tz, (float) (0.04 + 0.12 * k), 0.016f, 20,
                            ReplayPalette.alpha(ReplayPalette.RUBY, fadeOut * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                } else if (e.has(BACKFIRE)) {
                    ReplayDraw.star(glow, impact, f.right, f.up, (float) (0.12 * emphasis * fadeOut), 8, 0.4f,
                            ReplayPalette.alpha(ReplayPalette.EXPLOSION, fadeOut * a), ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                } else if (s.detail()) {
                    ReplayDraw.star(glow, impact, f.right, f.up, (float) (0.05 + 0.05 * k), 10, 0.4f,
                            ReplayPalette.alpha(ReplayPalette.LAMP, fadeOut * a), ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                }
                if (e.has(GUN_DROP)) warning(f, glow, d.transformPosition(e.fx, e.fy + 0.2f, e.fz, new Vector3f()), fadeOut * a);
            }
            case GRENADE_EXPLOSION, BOMB_DETONATE -> {
                for (int ring = 0; ring < 3; ring++) {
                    double rk = Math.min(1, k * 2 - ring * 0.2);
                    if (rk <= 0) continue;
                    ReplayDraw.ring(glow, d, x, y, z, (float) ((0.06 + 0.42 * ReplayStagePlan.easeOutCubic(rk)) * emphasis),
                            0.03f, 32, ReplayPalette.alpha(ReplayPalette.EXPLOSION, (1 - rk) * 0.9 * a),
                            ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                }
                Vector3f c = d.transformPosition(x, y + 0.08f, z, new Vector3f());
                float flash = (float) (0.22 * emphasis * (1 - k));
                ReplayDraw.billboard(glow, c, f.right, f.up, flash, flash, ReplayPalette.alpha(ReplayPalette.LAMP, 0.5 * fadeOut * a));
                if (e.node.kind() == Kind.BOMB_DETONATE) weapon(f, d, e, x, y + 0.24f, z, 0.12f, fadeOut * a);
            }
            case GRENADE_THROWN -> {
                if (e.hasFrom && e.hasTo) arc(glow, d, e, fadeOut * a, ReplayPalette.EXPLOSION);
                halo(glow, d, x, y, z, k, a, ReplayPalette.EXPLOSION, 0.12f);
            }
            case POISON -> {
                for (int ring = 0; ring < 3; ring++) {
                    double rk = (k * 1.4 - ring * 0.22);
                    if (rk <= 0 || rk >= 1) continue;
                    ReplayDraw.ring(glow, d, x, y, z, (float) (0.04 + 0.2 * rk), 0.016f, 24,
                            ReplayPalette.alpha(ReplayPalette.POISON, (1 - rk) * 0.85 * a), ReplayPalette.alpha(ReplayPalette.POISON, 0));
                }
                Vector3f c = d.transformPosition(x, y + 0.16f + (float) k * 0.05f, z, new Vector3f());
                ReplayDraw.billboard(glow, c, f.right, f.up, 0.025f, 0.04f, ReplayPalette.alpha(ReplayPalette.POISON, fadeOut * a));
            }
            case SHIELD_BROKEN -> {
                for (int i = 0; i < 6; i++) {
                    double ang = Math.PI * 2 * i / 6;
                    float r0 = (float) (0.06 + k * 0.18), r1 = r0 + 0.06f;
                    float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                    float c2 = (float) Math.cos(ang + 0.7), s2 = (float) Math.sin(ang + 0.7);
                    ReplayDraw.segment(glow, d, x + c * r0, y + 0.06f, z + sn * r0, x + c2 * r1, y + 0.06f + (float) k * 0.05f,
                            z + s2 * r1, 0.016f, ReplayPalette.alpha(ReplayPalette.SHIELD, fadeOut * a), ReplayPalette.alpha(ReplayPalette.SHIELD, 0.3 * fadeOut * a));
                }
            }
            case BOMB_PLANT -> {
                double blink = Math.sin(f.anim * 12) > 0 ? 1 : 0.25;
                Vector3f c = d.transformPosition(x, y + 0.03f, z, new Vector3f());
                ReplayDraw.billboard(glow, c, f.right, f.up, 0.03f, 0.03f, ReplayPalette.alpha(ReplayPalette.RUBY, blink * a));
                halo(glow, d, x, y, z, k, a, ReplayPalette.RUBY, 0.1f);
            }
            case BOMB_DEFUSE -> halo(glow, d, x, y, z, k, a, ReplayPalette.SHIELD, 0.16f);
            case PLAYER_REVIVAL -> {
                ReplayDraw.column(glow, d, x, y, z, 0.6f, 0.07f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.7 * fadeOut * a),
                        ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0));
                halo(glow, d, x, y, z, k, a, ReplayPalette.CHAMPAGNE, 0.2f);
            }
            case DISGUISE -> halo(glow, d, x, y, z, k, a, ReplayPalette.DISGUISE, 0.14f);
            case CHANGE_ROLE -> halo(glow, d, x, y, z, k, a, actorColor(f, e.node.actor()), 0.16f);
            case LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE -> {
                float r = 0.05f + (float) k * 0.04f;
                int c = ReplayPalette.alpha(e.node.kind() == Kind.DOOR_SEAL || e.node.kind() == Kind.DOOR_LOCK
                        ? ReplayPalette.SHIELD : ReplayPalette.LAMP, fadeOut * a);
                ReplayDraw.wireBox(glow, d, x - r, y, z - r, x + r, y + 0.12f, z + r, 0.01f, c);
            }
            default -> {
                int color = actorColor(f, e.node.actor());
                halo(glow, d, x, y, z, k, a, color, 0.1f);
                if (s.detail()) {
                    Vector3f c = d.transformPosition(x, y + 0.12f, z, new Vector3f());
                    ReplayDraw.star(glow, c, f.right, f.up, (float) (0.05 * fadeOut + 0.02), 6, (float) f.anim,
                            ReplayPalette.alpha(color, fadeOut * a), ReplayPalette.alpha(color, 0));
                }
            }
        }
    }

    private static void marker(ReplayFrame f, Matrix4f d, VertexConsumer glow, ReplayBoardModel.EventMark e, double a) {
        if (a <= 0.02) return;
        float x = e.tx, y = e.ty + 0.008f, z = e.tz;
        switch (e.node.kind()) {
            case KILL -> {
                float r = 0.035f;
                int c = ReplayPalette.alpha(ReplayPalette.RUBY, 0.95 * a);
                ReplayDraw.segment(glow, d, x - r, y, z - r, x + r, y, z + r, 0.014f, c, c);
                ReplayDraw.segment(glow, d, x - r, y, z + r, x + r, y, z - r, 0.014f, c, c);
                ReplayDraw.ring(glow, d, x, y, z, 0.05f, 0.008f, 16, ReplayPalette.alpha(ReplayPalette.RUBY, 0.5 * a),
                        ReplayPalette.alpha(ReplayPalette.RUBY, 0.1 * a));
                if (a > 0.5) weapon(f, d, e, x, y + 0.14f, z, 0.08f, a * 0.8);
            }
            case GUNSHOT -> ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.006f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.35 * a), ReplayPalette.alpha(e.has(HIT) ? ReplayPalette.RUBY : ReplayPalette.LAMP, 0.35 * a));
            case GRENADE_EXPLOSION, BOMB_DETONATE -> ReplayDraw.ring(glow, d, x, y, z, 0.07f, 0.012f, 20,
                    ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0.7 * a), ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0.1 * a));
            case POISON -> ReplayDraw.disc(glow, d, x, y, z, 0.035f, 12, ReplayPalette.alpha(ReplayPalette.POISON, 0.7 * a),
                    ReplayPalette.alpha(ReplayPalette.POISON, 0));
            default -> ReplayDraw.disc(glow, d, x, y, z, 0.022f, 10,
                    ReplayPalette.alpha(actorColor(f, e.node.actor()), 0.45 * a), ReplayPalette.alpha(ReplayPalette.BRASS, 0));
        }
    }

    private static void halo(VertexConsumer glow, Matrix4f d, float x, float y, float z, double k, double a, int color, float size) {
        double fadeOut = 1 - ReplayStagePlan.easeOutCubic(k);
        ReplayDraw.ring(glow, d, x, y, z, (float) (size * (0.4 + k)), 0.016f, 22, ReplayPalette.alpha(color, 0.85 * fadeOut * a),
                ReplayPalette.alpha(color, 0));
        ReplayDraw.disc(glow, d, x, y - 0.001f, z, size * 0.5f, 14, ReplayPalette.alpha(color, 0.3 * fadeOut * a),
                ReplayPalette.alpha(color, 0));
    }

    private static void arc(VertexConsumer glow, Matrix4f d, ReplayBoardModel.EventMark e, double a, int color) {
        Vector3f prev = d.transformPosition(e.fx, e.fy, e.fz, new Vector3f());
        float dist = (float) Math.sqrt((e.tx - e.fx) * (e.tx - e.fx) + (e.tz - e.fz) * (e.tz - e.fz));
        for (int i = 1; i <= 12; i++) {
            float u = i / 12f;
            float h = 4 * u * (1 - u) * Math.max(0.06f, dist * 0.35f);
            Vector3f p = d.transformPosition(e.fx + (e.tx - e.fx) * u, e.fy + (e.ty - e.fy) * u + h, e.fz + (e.tz - e.fz) * u, new Vector3f());
            if (i % 2 == 1) ReplayDraw.segment(glow, prev, p, 0.012f, ReplayPalette.alpha(color, 0.8 * a), ReplayPalette.alpha(color, 0.8 * a));
            prev = p;
        }
    }

    private static void warning(ReplayFrame f, VertexConsumer glow, Vector3f at, double a) {
        Vector3f top = new Vector3f(at).add(new Vector3f(f.up).mul(0.05f));
        Vector3f left = new Vector3f(at).sub(new Vector3f(f.right).mul(0.045f)).sub(new Vector3f(f.up).mul(0.03f));
        Vector3f right = new Vector3f(at).add(new Vector3f(f.right).mul(0.045f)).sub(new Vector3f(f.up).mul(0.03f));
        int c = ReplayPalette.alpha(ReplayPalette.LAMP, a);
        ReplayDraw.segment(glow, top, left, 0.012f, c, c);
        ReplayDraw.segment(glow, left, right, 0.012f, c, c);
        ReplayDraw.segment(glow, right, top, 0.012f, c, c);
        f.billboardText(new Vector3f(at).sub(new Vector3f(f.up).mul(0.005f)), Component.literal("!"), 0.004f, c);
    }

    /** Floating, slowly spinning 3D weapon model above a kill / detonation. */
    private static void weapon(ReplayFrame f, Matrix4f d, ReplayBoardModel.EventMark e, float x, float y, float z,
                               float size, double a) {
        if (a <= 0.1) return;
        Vector3f c = d.transformPosition(x, y, z, new Vector3f());
        ReplayDraw.billboard(f.glow(), c, f.right, f.up, size * 0.75f, size * 0.75f,
                ReplayPalette.alpha(e.icon.color(), 0.18 * a));
        float bob = (float) Math.sin(f.anim * 2.4 + e.node.tick()) * 0.012f;
        Matrix4f m = new Matrix4f(d).translate(x, y + bob, z).rotateY((float) (f.anim * 1.6 + e.node.tick() * 0.1)).scale(size * 1.6f);
        var stack = e.item();
        f.model(m, pose -> Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, f.modelBuffers(), f.level, 0));
    }

    private static int actorColor(ReplayFrame f, @Nullable java.util.UUID id) {
        ReplayBoardModel.Actor actor = f.model == null ? null : f.model.participant(id);
        return actor == null ? ReplayPalette.BRASS_LIGHT : actor.color;
    }

    // ------------------------------------------------------------------ briefing bar

    static void briefing(ReplayFrame f, ReplayBoardSite s) {
        ReplayBoardModel model = f.model;
        if (model == null || f.frame == null || f.frame.phase() != ReplayTimeline.Phase.PLAY) return;
        double t = f.replayTick;
        double hold = ReplayStagePlan.effectWindow(2.2, model.rate);
        LastMatchReplay.Node focus = null;
        for (LastMatchReplay.Node node : model.replay.events()) {
            if (node.tick() > t) break;
            if (node.highlight() && t - node.tick() <= hold) focus = node;
        }
        if (focus == null) return;
        double k = (t - focus.tick()) / hold;
        double a = s.fade() * Math.min(1, Math.min(k * 8, (1 - k) * 5));
        if (a <= 0.02) return;
        Component line = switch (focus.kind()) {
            case KILL -> focus.actor() == null
                    ? ReplayBoardTexts.tr("brief.death", ReplayBoardTexts.name(model.participantData(focus.target())), ReplayBoardTexts.weapon(focus))
                    : ReplayBoardTexts.tr("brief.kill", ReplayBoardTexts.name(model.participantData(focus.actor())),
                    ReplayBoardTexts.weapon(focus), ReplayBoardTexts.name(model.participantData(focus.target())));
            case GUNSHOT -> ReplayBoardTexts.tr("brief.shot", ReplayBoardTexts.name(model.participantData(focus.actor())),
                    ReplayBoardTexts.weapon(focus), ReplayBoardTexts.name(model.participantData(focus.target())));
            default -> ReplayBoardTexts.tr("brief.explosion", ReplayBoardTexts.name(model.participantData(focus.actor())));
        };
        float y = ReplayBoardSite.MAP_BASE + Math.max(0.35f, model.mapH + 0.22f);
        Matrix4f m = new Matrix4f(s.board()).translate(0, y, 0.0f);
        float w = Math.min(s.hw() * 2 - 0.2f, Minecraft.getInstance().font.width(line) * 0.0062f + 0.36f);
        float h = 0.13f;
        VertexConsumer fill = f.fill();
        ReplayDraw.rectXY(fill, m, -w / 2, -h / 2, w / 2, h / 2, 0, ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.88 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.88 * a));
        VertexConsumer glow = f.glow();
        int brass = ReplayPalette.alpha(ReplayPalette.BRASS, 0.9 * a);
        ReplayDraw.segment(glow, m, -w / 2, h / 2, 0.002f, w / 2, h / 2, 0.002f, 0.007f, brass, brass);
        ReplayDraw.segment(glow, m, -w / 2, -h / 2, 0.002f, w / 2, -h / 2, 0.002f, 0.007f, brass, brass);
        int ruby = ReplayPalette.alpha(ReplayPalette.RUBY, 0.95 * a);
        ReplayDraw.rectXY(fill, m, -w / 2, -h / 2, -w / 2 + 0.03f, h / 2, 0.001f, ruby, ruby);
        f.text(m, line, 0.015f, 0, 0.004f, 0.0062f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, w - 0.12f);
    }
}
