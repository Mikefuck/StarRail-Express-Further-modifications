package com.habitrain.core.client.tailor;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.tailor.DollTailorRules;
import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import com.habitrain.core.network.*;
import com.mojang.authlib.GameProfile;
import io.wifi.starrailexpress.client.SREClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.client.utils.InstinctManager;
import org.joml.Vector3f;
import java.util.*;

public final class DollTailorClient {
    private static DollTailorStatePayload state = new DollTailorStatePayload(null, 0, List.of());
    private static List<DollProtectionPayload.Link> links = List.of();
    private static final Map<UUID, Integer> completedAt = new HashMap<>();
    private static final Map<UUID, RemotePlayer> proxies = new HashMap<>();
    private static ClientLevel world;
    private static int ticks;
    private static int snapTicks;
    private static boolean resolving;
    private static final Vector3f[] THREADS = {new Vector3f(.85F, .65F, .9F), new Vector3f(.9F, .76F, .4F), new Vector3f(.6F, .85F, .85F)};
    private DollTailorClient() {}
    public static void init() {
        EntityRendererRegistry.register(DollTailorService.DOLL, TailorDollRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(DollTailorStatePayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            state = payload;
            if (ctx.client().screen instanceof DollTailorRosterView view) view.habitrain$tailorRoster(payload.entries());
        }));
        ClientPlayNetworking.registerGlobalReceiver(DollProtectionPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            if (!links.equals(payload.links())) SREClient.cachedHighLightMap.clear();
            links = payload.links();
            if (payload.snapped()) snapTicks = 12;
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(DollTailorClient::tick);
        HudRenderCallback.EVENT.register((g, delta) -> hud(g));
    }
    private static void reset() {
        state = new DollTailorStatePayload(null, 0, List.of()); links = List.of();
        completedAt.clear(); proxies.clear(); snapTicks = 0; resolving = false; world = null;
    }
    public static DollTailorStatePayload state() { return state; }
    public static int observed(UUID id) {
        return state.entries().stream().filter(e -> e.id().equals(id)).mapToInt(DollTailorStatePayload.Entry::observedTicks).findFirst().orElse(0);
    }
    public static boolean casting() {
        var self = Minecraft.getInstance().player;
        return self != null && self.isAlive() && !self.isSpectator() && state.sewingTicks() > 0
                && HabiRoles.isHabiRole(self, HabiRoles.DOLL_TAILOR_ID);
    }
    public static boolean sewing(Player player) {
        if (player == Minecraft.getInstance().player && casting()) return true;
        if (world == null) return false;
        for (Entity entity : world.entitiesForRendering())
            if (entity instanceof TailorDollEntity doll && !doll.complete() && player.getUUID().equals(doll.ownerId())) return true;
        return false;
    }
    public static boolean killerViewer() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator() || !mc.player.isAlive() || SREClient.gameComponent == null) return false;
        var role = SREClient.gameComponent.getRole(mc.player);
        return role != null && !role.isVigilanteTeam() && SREClient.gameComponent.isKillerTeamRole(role);
    }
    public static boolean hidden(Entity entity) {
        return !resolving && entity instanceof Player && killerViewer()
                && links.stream().anyMatch(link -> link.target().equals(entity.getUUID()));
    }
    public static OptionalInt highlight(TailorDollEntity doll) {
        if (!doll.complete() || !killerViewer() || world == null || doll.targetId() == null) return OptionalInt.empty();
        Player target = world.getPlayerByUUID(doll.targetId());
        if (target == null) {
            RemotePlayer proxy = proxies.computeIfAbsent(doll.getUUID(), id ->
                    new RemotePlayer(world, new GameProfile(doll.targetId(), doll.targetName())));
            proxy.setPos(doll.position()); target = proxy;
        }
        // Re-enter the original instinct rules without the substitution hook; do not give every killer unconditional glow.
        resolving = true;
        Vec3 originalPosition = target.position();
        try {
            // Preserve the real player's role/component state while evaluating range at the substitute.
            target.setPos(doll.position());
            return InstinctManager.getInstinctHighlight(target);
        } finally { target.setPos(originalPosition); resolving = false; }
    }
    public static boolean resolving() { return resolving; }
    public static float cameraPitch(float partial) {
        return (casting() ? (float) Math.sin((ticks + partial) * .26) * .28F : 0)
                + (snapTicks > 0 ? (float) Math.sin((ticks + partial) * 2.4) * snapTicks / 12F * .6F : 0);
    }
    public static float bounce(TailorDollEntity doll, float partial) {
        Integer start = completedAt.get(doll.getUUID());
        if (start == null) return 0;
        float age = ticks + partial - start;
        return age >= 0 && age < 10 ? (float) Math.sin(age * Math.PI / 10) * .12F : 0;
    }
    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { if (world != null) reset(); return; }
        if (world != mc.level) {
            if (world != null) reset();
            world = mc.level;
        }
        ticks++; if (snapTicks > 0) snapTicks--;
        if (casting()) {
            mc.player.input.leftImpulse = mc.player.input.forwardImpulse = 0;
            mc.player.input.jumping = mc.player.input.shiftKeyDown = false;
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false); mc.options.keyJump.setDown(false);
        }
        Set<UUID> visible = new HashSet<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof TailorDollEntity doll)) continue;
            visible.add(doll.getUUID());
            if (doll.complete()) {
                if (!completedAt.containsKey(doll.getUUID())) completedAt.put(doll.getUUID(), ticks - 20);
                if (ticks % 35 == 0) particle(ParticleTypes.SNOWFLAKE, doll.position().add(0, 1.1, 0), new Vec3(.005, -.015, 0));
            } else {
                completedAt.put(doll.getUUID(), ticks);
                double angle = ticks * .42;
                float p = doll.stitches() / (float) DollTailorRules.SEWING_TICKS;
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * Math.PI * 2 / 3;
                    Vec3 pos = doll.position().add(Math.cos(a) * .48, .1 + (ticks * .045 + i * .6) % 1.85, Math.sin(a) * .48);
                    particle(new DustParticleOptions(THREADS[i], .45F), pos, new Vec3(0, .012, 0));
                    if (ticks % 3 == 0) particle(ParticleTypes.ENCHANT, pos, doll.position().add(0, 1, 0).subtract(pos).scale(.1));
                }
                if (ticks % 4 == 0) particle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.WHITE_WOOL.defaultBlockState()),
                        doll.position().add((mc.level.random.nextDouble() - .5) * .7, .15 + p * 1.6, 0), new Vec3(0, .015, 0));
                if (doll.stitches() >= 180 && doll.stitches() < 183) particle(ParticleTypes.SWEEP_ATTACK, doll.position().add(0, 1.1, 0), Vec3.ZERO);
                if (ticks % 6 == 0) sound(doll.position(), SoundEvents.WOOL_HIT, .16F, 1.6F);
                if (ticks % 13 == 0) sound(doll.position(), SoundEvents.SHEEP_SHEAR, .12F, 1.7F);
                if (ticks % 20 == 0) sound(doll.position(), SoundEvents.WARDEN_HEARTBEAT, .12F, 1.5F);
            }
        }
        completedAt.keySet().retainAll(visible); proxies.keySet().retainAll(visible);
        if (ticks % 4 == 0) {
            for (var link : links) {
                if (!mc.player.getUUID().equals(link.owner()) && !mc.player.getUUID().equals(link.target())) continue;
                Player target = mc.level.getPlayerByUUID(link.target());
                if (target == null || target.position().distanceToSqr(link.position()) > 48 * 48) continue;
                Vec3 from = link.position().add(0, .9, 0), to = target.position().add(0, .9, 0);
                int count = Math.min(20, Math.max(2, (int) from.distanceTo(to) * 2));
                for (int i = 0; i <= count; i++) particle(new DustParticleOptions(new Vector3f(.72F, .6F, .3F), .23F), from.lerp(to, i / (double) count), Vec3.ZERO);
            }
            Player target = state.selected() == null ? null : mc.level.getPlayerByUUID(state.selected());
            if (!casting() && target != null && observed(target.getUUID()) < DollTailorRules.OBSERVATION_TICKS
                    && DollTailorRules.canObserve(mc.player, target)) {
                particle(ParticleTypes.SNOWFLAKE, mc.player.getEyePosition().lerp(target.getEyePosition(), .6), new Vec3(0, .01, 0));
                particle(new DustParticleOptions(new Vector3f(.92F, .9F, .86F), .3F),
                        mc.player.getEyePosition().lerp(target.getEyePosition(), .35), Vec3.ZERO);
            }
        }
    }
    private static void particle(net.minecraft.core.particles.ParticleOptions type, Vec3 p, Vec3 v) {
        world.addParticle(type, p.x, p.y, p.z, v.x, v.y, v.z);
    }
    private static void sound(Vec3 p, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        world.playLocalSound(p.x, p.y, p.z, sound, SoundSource.PLAYERS, volume, pitch, false);
    }
    private static void hud(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator()) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        if (casting()) {
            int edge = Math.min(w, h) / 5;
            int shade = (int) (38 + 5 * Math.sin(ticks * .2)) << 24 | 0x362B35;
            g.fillGradient(0, 0, w, edge, shade, 0x00362B35);
            g.fillGradient(0, h - edge, w, h, 0x00362B35, shade);
            for (int i = 0; i < edge; i += 3) {
                int color = ((edge - i) * 30 / edge) << 24 | 0x362B35;
                g.fill(i, edge, i + 3, h - edge, color); g.fill(w - i - 3, edge, w - i, h - edge, color);
            }
            float p = Math.min(1F, state.sewingTicks() / (float) DollTailorRules.SEWING_TICKS);
            threadRing(g, cx, cy, 24, p);
            g.drawCenteredString(mc.font, Component.translatable("hud.habitrain_core.doll_tailor.sewing",
                    String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, 200 - state.sewingTicks()) / 20F)), cx, cy + 34, 0xEEE6D6);
        } else if (HabiRoles.isHabiRole(mc.player, HabiRoles.DOLL_TAILOR_ID) && state.selected() != null) {
            var entry = state.entries().stream().filter(e -> e.id().equals(state.selected())).findFirst().orElse(null);
            if (entry != null) {
                float p = entry.observedTicks() / (float) DollTailorRules.OBSERVATION_TICKS;
                threadRing(g, cx + 30, cy, 10, p);
                var label = Component.translatable(p >= 1 ? "hud.habitrain_core.doll_tailor.ready" : "hud.habitrain_core.doll_tailor.observing",
                        entry.name(), (int) (p * 100));
                g.drawString(mc.font, mc.font.substrByWidth(label, Math.max(0, w - cx - 50)).getString(), cx + 46, cy - 4, 0xDABF89, true);
            }
        }
        if (links.stream().anyMatch(link -> link.target().equals(mc.player.getUUID()))) {
            int x = w - 105, y = h - 62, alpha = 180 + (int) (40 * Math.sin(ticks * .07));
            int color = alpha << 24 | 0xDABF89;
            g.fill(x, y + 2, x + 7, y + 9, color); g.fill(x + 1, y - 3, x + 6, y + 2, color);
            g.fill(x - 2, y + 3, x + 9, y + 5, color); g.fill(x, y + 9, x + 2, y + 13, color); g.fill(x + 5, y + 9, x + 7, y + 13, color);
            g.drawString(mc.font, Component.translatable("hud.habitrain_core.doll_tailor.protected"), x + 13, y + 2, color, true);
        }
    }
    private static void threadRing(GuiGraphics g, int cx, int cy, int radius, float progress) {
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16 - Math.PI / 2;
            int x = cx + (int) (Math.cos(a) * radius), y = cy + (int) (Math.sin(a) * radius);
            g.fill(x, y, x + 2, y + 2, i < progress * 32 ? 0xEEDABF89 : 0x5575687A);
        }
    }
}
