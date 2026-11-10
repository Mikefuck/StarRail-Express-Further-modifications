package com.habitrain.core.client.idiot;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.idiot.*;
import com.habitrain.core.network.IdiotEffectPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.ChickenRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.particles.*;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.util.Locale;

/** Local presentation only; disabling cosmetics cannot affect server-side gameplay. */
public final class IdiotClient {
    private static int boost, pity, face, jackpot, ticks;
    private static ClientLevel world;
    private static final Vector3f YOLK = new Vector3f(1, .78F, .15F);
    private IdiotClient() {}
    public static void init() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("idiotvisual")
                        .executes(ctx -> {
                            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new IdiotVisualScreen(null)));
                            return 1;
                        })));
        EntityRendererRegistry.register(IdiotContent.PROJECTILE, ThrownItemRenderer::new);
        EntityRendererRegistry.register(IdiotContent.CHICK, ctx -> new ChickenRenderer(ctx) {
            @Override protected boolean shouldShowName(Chicken chick) {
                var player = Minecraft.getInstance().player;
                return player != null && chick.hasCustomName() && player.distanceToSqr(chick) < 36 && player.hasLineOfSight(chick);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(IdiotEffectPayload.TYPE, (payload, ctx) -> receive(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(IdiotClient::tick);
        HudRenderCallback.EVENT.register((g, delta) -> hud(g));
    }
    private static void reset() { boost = pity = face = jackpot = ticks = 0; world = null; }
    private static void receive(IdiotEffectPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (world != mc.level) { reset(); world = mc.level; }
        switch (payload.event()) {
            case IdiotEffectPayload.STATE -> { boost = payload.boostTicks(); pity = payload.pityRemaining(); }
            case IdiotEffectPayload.FACE -> {
                face = 12;
                mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        SoundEvents.SLIME_BLOCK_HIT, SoundSource.PLAYERS, .35F, 1.6F, false);
            }
            case IdiotEffectPayload.JACKPOT -> {
                boost = payload.boostTicks(); jackpot = 45;
                sound(SoundEvents.CHICKEN_AMBIENT, 1.8F);
                sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F);
            }
            case IdiotEffectPayload.SPLASH -> burst(payload.position(), false);
            case IdiotEffectPayload.HATCH -> burst(payload.position(), true);
            default -> { }
        }
    }
    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { reset(); return; }
        if (world != mc.level) { reset(); world = mc.level; }
        ticks++;
        if (boost > 0) boost--;
        if (face > 0) face--;
        if (jackpot > 0) {
            jackpot--;
            if (jackpot == 41) sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5F);
            if (jackpot == 37) sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.9F);
        }
        if (!mc.player.isAlive() || mc.player.isSpectator()) { boost = pity = face = jackpot = 0; return; }
        int density = ClientVisualPreferences.getIdiotParticles();
        if (density == 0 || ticks % (density == 1 ? 6 : 3) != 0) return;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 32 * 32) continue;
            if (entity instanceof IdiotEggEntity) {
                particle(new DustParticleOptions(YOLK, .55F), entity.position(), new Vec3(0, .008, 0));
                if (ticks % 6 == 0) feather(entity.position());
            } else if (entity instanceof IdiotChickEntity && ticks % 12 == 0) {
                feather(entity.position().add(0, .2, 0));
                particle(new DustParticleOptions(new Vector3f(.55F, .8F, 1), .6F), entity.position().add(0, .3, 0), new Vec3(0, .03, 0));
            }
        }
        for (var player : mc.level.players()) {
            var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            boolean boosted = player == mc.player ? boost > 0 : speed != null && speed.getModifier(HabiTrainCore.id("idiot_speed")) != null;
            if (!boosted || player.distanceToSqr(mc.player) > 32 * 32) continue;
            boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > .002;
            if (moving || ticks % 12 == 0) {
                Vec3 pos = player.position().add((mc.level.random.nextDouble() - .5) * .4, .1, (mc.level.random.nextDouble() - .5) * .4);
                feather(pos);
                particle(ParticleTypes.CLOUD, pos, player.getDeltaMovement().scale(-.1));
            }
        }
    }
    private static void burst(Vec3 pos, boolean hatch) {
        var level = Minecraft.getInstance().level;
        int density = ClientVisualPreferences.getIdiotParticles();
        if (level == null || density == 0) return;
        int count = (hatch ? 22 : 12) / (density == 1 ? 3 : 1);
        for (int i = 0; i < count; i++) {
            Vec3 velocity = new Vec3((level.random.nextDouble() - .5) * .16, level.random.nextDouble() * .16, (level.random.nextDouble() - .5) * .16);
            particle(new ItemParticleOption(ParticleTypes.ITEM, (hatch ? Items.FEATHER : Items.EGG).getDefaultInstance()), pos, velocity);
            Vector3f color = hatch ? new Vector3f(level.random.nextFloat(), .6F + level.random.nextFloat() * .4F, level.random.nextFloat())
                    : i % 2 == 0 ? YOLK : new Vector3f(1, 1, .9F);
            particle(new DustParticleOptions(color, hatch ? 1.1F : .8F), pos, velocity);
            if (hatch && i % 3 == 0) particle(ParticleTypes.FIREWORK, pos, velocity);
            if (hatch && i % 7 == 0) particle(ParticleTypes.HEART, pos.add(0, .2, 0), Vec3.ZERO);
        }
    }
    private static void feather(Vec3 pos) {
        particle(new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER.getDefaultInstance()), pos, new Vec3(0, .025, 0));
    }
    private static void particle(ParticleOptions particle, Vec3 pos, Vec3 velocity) {
        var level = Minecraft.getInstance().level;
        if (level != null) level.addParticle(particle, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
    }
    private static void sound(net.minecraft.sounds.SoundEvent event, float pitch) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.playSound(event, .55F, pitch);
    }
    private static void hud(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator() || !mc.player.isAlive()) return;
        int w = g.guiWidth(), h = g.guiHeight();
        if (face > 0 && ClientVisualPreferences.isIdiotFaceOverlay()) {
            int alpha = (int) (Math.min(1F, face / 8F) * 90) << 24;
            int width = Math.max(7, w / 22);
            // Separate edge splats, leaving the crosshair and the middle of the image clear.
            for (int i = 0; i < 4; i++) {
                int y = h * (i + 1) / 5;
                splat(g, 0, y, width + i % 2 * 5, Math.max(5, h / 20), alpha, false);
                splat(g, w, y + 5, width + i % 2 * 5, Math.max(5, h / 20), alpha, true);
            }
        }
        if (!HabiRoles.isHabiRole(mc.player, HabiRoles.IDIOT_ID)) return;
        if (boost > 0 || pity > 0) {
            Component text = boost > 0 ? Component.translatable("hud.habitrain_core.idiot.boost", String.format(Locale.ROOT, "%.1f", boost / 20F))
                    : Component.translatable("hud.habitrain_core.idiot.pity", pity);
            int x = Math.max(4, w - mc.font.width(text) - 10), y = Math.max(4, h - 65);
            g.fill(x - 4, y - 3, w - 6, y + 12, 0xA52A2519);
            g.drawString(mc.font, text, x, y, 0xFFFFDF70);
        }
        if (jackpot > 0) {
            if (ClientVisualPreferences.isIdiotLargeJackpot()) {
                float scale = Math.min(1.5F, (w - 16F) / Math.max(1, mc.font.width(Component.translatable("hud.habitrain_core.idiot.jackpot"))));
                g.pose().pushPose();
                g.pose().translate(w / 2F, h / 4F, 0); g.pose().scale(scale, scale, 1);
                g.drawCenteredString(mc.font, Component.translatable("hud.habitrain_core.idiot.jackpot"), 0, 0, 0xFFFFDD59);
                g.drawCenteredString(mc.font, Component.translatable("hud.habitrain_core.idiot.reward"), 0, 13, 0xFFFFFFEA);
                g.pose().popPose();
            } else g.drawCenteredString(mc.font, Component.translatable("hud.habitrain_core.idiot.reward"), w / 2, h / 4, 0xFFFFDD59);
        }
    }
    private static void splat(GuiGraphics g, int edge, int y, int width, int height, int alpha, boolean right) {
        int x = right ? edge - width : edge;
        g.fill(x + 2, y - 2, x + width - 2, y + height + 2, alpha | 0xFFF2B3);
        g.fill(x, y + 1, x + width, y + height - 1, alpha | 0xFFF2B3);
        int yolk = x + width / 3;
        g.fill(yolk, y + 1, yolk + Math.max(3, width / 3), y + height - 1, alpha | 0xFFCA39);
        int drip = right ? x + width / 4 : x + width * 3 / 4;
        g.fill(drip, y + height + 2, drip + 2, y + height + 7, alpha | 0xFFF2B3);
    }
}
