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
 * 基于 SRE 的 RoundTextRenderer 报幕逻辑简化实现，仅包含欢迎动画 + 音效。
 * 无游戏结束渲染，不依赖 SRE 内部类。
 */
public class BlackoutWelcomeRenderer {
    private static final int WELCOME_DURATION = 200;
    private static String roleName = "";
    private static String subtitle = "";
    private static String goal = "";
    private static int welcomeTime = 0;

    /** 启动报幕动画 */
    public static void startWelcome(String name, String sub, String g, int killers, int targets) {
        roleName = "§6§l你是 " + name;
        subtitle = sub;
        goal = g;
        welcomeTime = WELCOME_DURATION;
    }

    public static boolean isActive() { return welcomeTime > 0; }

    public static void tick() {
        if (welcomeTime <= 0) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { welcomeTime = 0; return; }

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
        welcomeTime--;
    }

    public static void render(GuiGraphics g) {
        if (welcomeTime <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float cx = w / 2f;
        float cy = h / 2f + 3.5f;

        // 角色名 (tick 200-181)
        if (welcomeTime <= 200 && welcomeTime > 120) {
            var txt = Component.literal(roleName);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(2.6f, 2.6f, 1f);
            g.drawCenteredString(font, txt, 0, -12, 0xFFFFFF);
            g.pose().popPose();
        }
        // 副标题 (tick 180-121)
        if (welcomeTime <= 180 && welcomeTime > 60) {
            var txt = Component.literal(subtitle);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().scale(1.2f, 1.2f, 1f);
            g.drawCenteredString(font, txt, 0, 0, 0xFFFFFF);
            g.pose().popPose();
        }
        // 目标 (tick 120-61)
        if (welcomeTime <= 120 && welcomeTime > 0) {
            var txt = Component.literal(goal);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.drawCenteredString(font, txt, 0, 14, 0xFFFFFF);
            g.pose().popPose();
        }
    }

    public static void reset() {
        welcomeTime = 0;
        roleName = "";
        subtitle = "";
        goal = "";
    }
}
