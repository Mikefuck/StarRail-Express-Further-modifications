package com.habitrain.core.client;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.event.MutableComponentResult;
import org.agmas.noellesroles.client.event.OnMessageBelowMoneyRenderer;

/**
 * 出局玩家往返等待房间的命令提示，挂在上游右上角「金币下方信息行」里，
 * 与上游 {@code /vt_mode} 的死亡语音提示同一处、同一种写法。
 */
@Environment(EnvType.CLIENT)
public final class EliminatedRestCommandTip {
    private static boolean registered;

    private EliminatedRestCommandTip() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        OnMessageBelowMoneyRenderer.EVENT.register((client, graphics, deltaTracker) -> {
            if (!EliminatedRestPromptState.canToggle() || client == null || client.player == null) {
                return null;
            }
            // visible=可进入（旁观中）；否则 canToggle 只剩「正在等待房间」一种情况。
            String key = EliminatedRestPromptState.isVisible()
                    ? "message.tip.habitrain_core.rest_area.enter"
                    : "message.tip.habitrain_core.rest_area.return";
            return new MutableComponentResult(Component.translatable(key,
                            Component.literal("/" + EliminatedRestAreaService.REST_COMMAND)
                                    .withStyle(ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.WHITE));
        });
    }
}
