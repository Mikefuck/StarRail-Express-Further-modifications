package com.habitrain.core.client.mixin;

import com.habitrain.core.client.util.TaskTextNormalizer;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 归一化左上角任务 HUD 文本。SRE 4.3.0 只有
 * {@code HudMoodRenderer$TaskRenderer}；不存在的 {@code MoodRenderer$TaskRenderer}
 * 不能列入 {@code @Mixin(targets)}（多 target 必须全部解析，缺一类则整类跳过）。
 */
@Mixin(targets = "io.wifi.starrailexpress.client.gui.HudMoodRenderer$TaskRenderer")
public class FixTaskRendererMixin {

    @Shadow(remap = false)
    private Component text;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void habitrain$fixTaskText(SREPlayerTaskComponent.TrainTask task, float delta,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (task == null) {
            return;
        }

        // 专属任务（电话/恢复供电）已插入 SRE map 以显示左上角，走正常归一化，不再清空。

        Component taskNameComponent = TaskTextNormalizer.normalizeTaskTitle(task);
        // 杀手任务显示"你可以假装去..."（SRE 原生语义）；本 mod 不再区分"真/假任务"——
        // 杀手双任务机制已删除，所有模组任务都是真实任务。
        boolean killer = SREClient.isKiller();
        // 关灯模式警长虽因 canUseKiller=true 被判为 killer，但任务都是真实有效的，
        // 不应显示"你可以假装去..."的 killer 前缀。
        if (killer) {
            SREGameWorldComponent gc = SREClient.gameComponent;
            var self = Minecraft.getInstance().player;
            if (gc != null && self != null) {
                SRERole role = gc.getRole(self);
                if (role != null && role.isVigilanteTeam()) {
                    killer = false;
                }
            }
        }

        this.text = Component.translatable("task." + (killer ? "fake" : "feel"))
                .append(taskNameComponent);
    }
}