package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.exmo.sre.subtitle.SubtitleCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 屏蔽 SRE 原版 TOP 字幕中的两类噪声：
 * <ul>
 *   <li>{@code subtitle.task.parallel} — 「并列任务已出现」</li>
 *   <li>主标题为 {@code task.*} 翻译键 — 自定义任务会被 SRE 拼成 {@code task.中文名}，
 *       与 {@code DlcTaskTracker.sendNewTaskTop} 的字面量标题重复闪烁</li>
 * </ul>
 * 任务生成逻辑不变，仅去掉提示。
 */
@Mixin(SREPlayerTaskComponent.class)
public abstract class SuppressParallelTaskSubtitleMixin {

    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/exmo/sre/subtitle/SubtitleCommand;sendToPlayerTop(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Component;IZ)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void habitrain$suppressParallelSubtitle(ServerPlayer player, Component title, Component sub,
                                                     int duration, boolean broadcast) {
        if (sub != null && sub.getContents() instanceof TranslatableContents tc
                && "subtitle.task.parallel".equals(tc.getKey())) {
            return;
        }
        // Drop SRE raw-key new-task toast (task.<displayName>); keep DlcTaskTracker literal toast.
        if (title != null && title.getContents() instanceof TranslatableContents mainTc
                && mainTc.getKey() != null && mainTc.getKey().startsWith("task.")) {
            return;
        }
        SubtitleCommand.sendToPlayerTop(player, title, sub, duration, broadcast);
    }
}
