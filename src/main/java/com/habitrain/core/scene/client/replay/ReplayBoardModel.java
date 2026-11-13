package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayDeathIcons;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything derived from one {@link LastMatchReplay} that does not change per frame: the diorama
 * frame, per-player samplers with pre-projected trail points, event anchors and text. Rebuilt only
 * when the client receives a new summary object.
 *
 * <p>"Diorama frame" (D): origin at the centre of the tray surface, +X along the map's long axis
 * (the viewer's right), +Y up, scaled to blocks. The source map is rotated a quarter turn when its
 * Z extent is the longer one, so the train always lies across the plaque.</p>
 */
final class ReplayBoardModel {
    final LastMatchReplay replay;
    final ReplayTimeline timeline;
    final ReplayFocusPlan focus;
    final @Nullable ReplayProjection.Layout layout;
    final SceneBounds source;
    final boolean rotated;
    final float scale, mapW, mapD, mapH;
    final Actor[] actors;
    final Map<UUID, Actor> byId = new HashMap<>();
    final EventMark[] events;
    final double rate, comet;
    final Component title, subtitle, modeLabel;
    final int winColor;
    final List<Actor> winners;
    final @Nullable Actor mvp;
    final @Nullable LastMatchReplay.Node firstKill, lastKill;
    final int maxSurvival;

    ReplayBoardModel(LastMatchReplay replay, ReplayTimeline timeline) {
        this.replay = replay;
        this.timeline = timeline;
        this.focus = new ReplayFocusPlan(replay, timeline);
        this.layout = ReplayProjection.layout(replay);
        this.source = layout == null ? SceneBounds.EMPTY : layout.source();
        this.rotated = source.sizeZ() > source.sizeX();
        this.scale = layout == null ? 0.05f : (float) layout.scale();
        this.mapW = (rotated ? source.sizeZ() : source.sizeX()) * scale;
        this.mapD = (rotated ? source.sizeX() : source.sizeZ()) * scale;
        this.mapH = source.sizeY() * scale;
        this.rate = ReplayStagePlan.playbackRate(replay.durationTicks(), timeline.cycleTicks());
        this.comet = ReplayStagePlan.cometTicks(rate);

        List<LastMatchReplay.Participant> participants = replay.participants();
        actors = new Actor[participants.size()];
        float[] tmp = new float[3];
        int survival = 1;
        for (int i = 0; i < participants.size(); i++) {
            var p = participants.get(i);
            var sampler = new ReplayTrackSampler(p.track());
            float[] xs = new float[sampler.size()], ys = new float[sampler.size()], zs = new float[sampler.size()];
            for (int s = 0; s < sampler.size(); s++) {
                project(sampler.x(s), sampler.y(s), sampler.z(s), tmp);
                xs[s] = tmp[0]; ys[s] = tmp[1]; zs[s] = tmp[2];
            }
            int fallback = switch (p.faction()) {
                case KILLER -> ReplayPalette.KILLER;
                case NEUTRAL -> ReplayPalette.NEUTRAL;
                default -> ReplayPalette.PASSENGER;
            };
            Actor actor = new Actor(i, p, sampler, xs, ys, zs, ReplayPalette.opaque(p.endColor(), fallback),
                    p.id().equals(replay.mvp()));
            actors[i] = actor;
            byId.put(p.id(), actor);
            survival = Math.max(survival, p.survivalSeconds());
        }
        maxSurvival = survival;

        List<EventMark> marks = new ArrayList<>();
        for (LastMatchReplay.Node node : replay.events()) {
            if (node.from() == null && node.to() == null) continue;
            marks.add(new EventMark(node, this, tmp));
        }
        events = marks.toArray(EventMark[]::new);

        title = ReplayBoardTexts.winTitle(replay);
        subtitle = ReplayBoardTexts.winSubtitle(replay);
        modeLabel = ReplayBoardTexts.mode(replay.modeId());
        winColor = ReplayBoardTexts.winColor(replay);
        List<Actor> won = new ArrayList<>();
        for (Actor actor : actors) if (actor.p.winner()) won.add(actor);
        won.sort(Comparator.comparing((Actor a) -> !a.mvp).thenComparing(a -> -a.p.score()).thenComparing(a -> a.p.name()));
        winners = List.copyOf(won);
        Actor chosen = replay.mvp() == null ? null : byId.get(replay.mvp());
        if (chosen == null && !won.isEmpty()) chosen = won.getFirst();
        mvp = chosen;
        firstKill = replay.firstKill();
        lastKill = replay.lastKill();
    }

    @Nullable Actor participant(@Nullable UUID id) {
        return id == null ? null : byId.get(id);
    }

    @Nullable LastMatchReplay.Participant participantData(@Nullable UUID id) {
        Actor actor = participant(id);
        return actor == null ? null : actor.p;
    }

    /** Origin-relative block coordinates -> diorama frame (before the tray height offset). */
    void project(double bx, double by, double bz, float[] out) {
        if (layout == null) { out[0] = out[1] = out[2] = 0; return; }
        SceneBounds o = layout.origin();
        double x = (o.minX() - source.minX() + bx - source.sizeX() * 0.5) * scale;
        double y = (o.minY() - source.minY() + by) * scale;
        double z = (o.minZ() - source.minZ() + bz - source.sizeZ() * 0.5) * scale;
        if (rotated) {
            out[0] = (float) z;
            out[2] = (float) -x;
        } else {
            out[0] = (float) x;
            out[2] = (float) z;
        }
        out[1] = (float) y;
    }

    boolean blackoutAt(double tick) {
        for (LastMatchReplay.Span span : replay.spans()) {
            if (span.kind() == LastMatchReplay.SpanKind.BLACKOUT && span.startTick() <= tick && tick < span.endTick()) return true;
        }
        return false;
    }

    boolean psychoAt(UUID actor, double tick) {
        for (LastMatchReplay.Span span : replay.spans()) {
            if (span.kind() == LastMatchReplay.SpanKind.PSYCHO && actor.equals(span.actor())
                    && span.startTick() <= tick && tick < span.endTick()) return true;
        }
        return false;
    }

    static final class Actor {
        final int index;
        final LastMatchReplay.Participant p;
        final ReplayTrackSampler track;
        final float[] xs, ys, zs;
        final int color;
        final boolean mvp;
        final Component name, role;

        Actor(int index, LastMatchReplay.Participant p, ReplayTrackSampler track, float[] xs, float[] ys, float[] zs,
              int color, boolean mvp) {
            this.index = index;
            this.p = p;
            this.track = track;
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
            this.color = color;
            this.mvp = mvp;
            this.name = ReplayBoardTexts.name(p);
            this.role = ReplayBoardTexts.role(p.endRole());
        }

        int factionColor() {
            return p.faction() == MatchWinFaction.KILLER ? ReplayPalette.KILLER
                    : p.faction() == MatchWinFaction.NEUTRAL ? ReplayPalette.NEUTRAL : ReplayPalette.PASSENGER;
        }
    }

    static final class EventMark {
        final LastMatchReplay.Node node;
        final boolean hasFrom, hasTo;
        final float fx, fy, fz, tx, ty, tz;
        final ReplayDeathIcons.Icon icon;
        private ItemStack item;

        EventMark(LastMatchReplay.Node node, ReplayBoardModel model, float[] tmp) {
            this.node = node;
            hasFrom = node.from() != null;
            hasTo = node.to() != null;
            LastMatchReplay.Position a = hasFrom ? node.from() : node.to();
            LastMatchReplay.Position b = hasTo ? node.to() : node.from();
            model.project(a.blockX(), a.blockY(), a.blockZ(), tmp);
            fx = tmp[0]; fy = tmp[1]; fz = tmp[2];
            model.project(b.blockX(), b.blockY(), b.blockZ(), tmp);
            tx = tmp[0]; ty = tmp[1]; tz = tmp[2];
            icon = node.kind() == LastMatchReplay.Kind.KILL ? ReplayDeathIcons.classify(node.detail()) : ReplayDeathIcons.Icon.SKULL;
        }

        boolean has(int flag) { return (node.flags() & flag) != 0; }

        /** Weapon model for kill / gunshot nodes, resolved lazily against the live item registry. */
        ItemStack item() {
            if (item != null) return item;
            Item resolved = Items.AIR;
            if (node.kind() == LastMatchReplay.Kind.GUNSHOT) resolved = lookup(node.detail());
            if (resolved == Items.AIR) {
                for (String candidate : ReplayDeathIcons.itemCandidates(icon)) {
                    resolved = lookup(candidate);
                    if (resolved != Items.AIR) break;
                }
            }
            item = resolved == Items.AIR ? new ItemStack(Items.SKELETON_SKULL) : new ItemStack(resolved);
            return item;
        }

        private static Item lookup(String id) {
            ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return Items.AIR;
            return BuiltInRegistries.ITEM.get(key);
        }
    }
}
