package com.habitrain.core.client.gui;

import io.wifi.starrailexpress.index.TMMSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * 停电模式开局报幕渲染器。
 * <p>
 * 可变状态已移至 {@link ClientBlackoutState}，本类仅负责渲染和音效逻辑。
 */
public class BlackoutWelcomeRenderer {

    /** 启动报幕动画 —— 委托给 {@link ClientBlackoutState} */
    public static void startWelcome(String name, String sub, String g) {
        ClientBlackoutState.startWelcome(name, sub, g);
    }

    public static boolean isActive() {
        return ClientBlackoutState.isWelcomeActive();
    }

    public static void tick() {
        if (!ClientBlackoutState.isWelcomeActive()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { ClientBlackoutState.resetWelcome(); return; }

        int welcomeTime = ClientBlackoutState.getWelcomeTime();
        switch (welcomeTime) {
            case 200 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_RISER, SoundSource.MASTER, 10f, 1f, player.getRandom().nextLong());
            case 180 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.25f, player.getRandom().nextLong());
            case 120 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.5f, player.getRandom().nextLong());
            case 60 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO, SoundSource.MASTER, 10f, 1.75f, player.getRandom().nextLong());
            case 1 -> player.level().playSeededSound(player, player.getX(), player.getY(), player.getZ(),
                    TMMSounds.UI_PIANO_STINGER, SoundSource.MASTER, 10f, 1f, player.getRandom().nextLong());
        }
        ClientBlackoutState.decrementWelcomeTime();
    }

    public static void render(GuiGraphics g) {
        if (!ClientBlackoutState.isWelcomeActive()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float cx = w / 2f;
        float cy = h / 2f + 3.5f;

        int welcomeTime = ClientBlackoutState.getWelcomeTime();

        // 角色名 (tick 180-0, 累加显示后常驻)
        if (welcomeTime <= 180) {
            var txt = Component.literal(ClientBlackoutState.getWelcomeRoleName());
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(2.6f, 2.6f, 1f);
            g.drawCenteredString(font, txt, 0, -12, 0xFFFFFF);
            g.pose().popPose();
        }
        // 副标题 (tick 120-0, 累加显示后常驻)
        if (welcomeTime <= 120) {
            var txt = Component.literal(ClientBlackoutState.getWelcomeSubtitle());
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(1.2f, 1.2f, 1f);
            g.drawCenteredString(font, txt, 0, 0, 0xFFFFFF);
            g.pose().popPose();
        }
        // 目标 (tick 60-0, 累加显示后常驻)
        if (welcomeTime <= 60) {
            var txt = Component.literal(ClientBlackoutState.getWelcomeGoal());
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.drawCenteredString(font, txt, 0, 14, 0xFFFFFF);
            g.pose().popPose();
        }
    }

    public static void reset() {
        ClientBlackoutState.resetWelcome();
    }

    private BlackoutWelcomeRenderer() {
    }
}
