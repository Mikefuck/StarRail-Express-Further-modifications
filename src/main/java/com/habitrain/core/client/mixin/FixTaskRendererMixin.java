package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.util.TaskTextNormalizer;
import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import com.habitrain.core.api.TaskInstance;
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
 * 同时作用于 SRE 的两个内部 TaskRenderer 宿主类，归一化其显示文本。
 * <p>
 * 脆弱性说明：两个目标类都必须存在且都声明 {@code private Component text} 字段，
 * 任一类被 SRE 重命名或字段签名变更都会导致 mixin 应用失败。
 * 若 SRE 后续移除其中一个渲染器，需同步从这里删掉对应 target。
 */
@Mixin(targets = {
    "io.wifi.starrailexpress.client.gui.HudMoodRenderer$TaskRenderer",
    "io.wifi.starrailexpress.client.gui.MoodRenderer$TaskRenderer"
})
public class FixTaskRendererMixin {

    @Shadow(remap = false)
    private Component text;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void habitrain$fixTaskText(SREPlayerTaskComponent.TrainTask task, float delta,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (task == null) {
            return;
        }

        Component taskNameComponent = TaskTextNormalizer.normalizeTaskTitle(task);
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

        // 杀手双任务区分：只有假任务（来自好人任务池，包装为 SRETrainTaskWrapper 且
        // 在 ActiveTaskCache.fakeTaskFullId 中追踪）才显示"你可以假装去..."前缀；
        // 真任务（坏人任务池）显示"感觉要去..."前缀，避免两个任务都带"假装"。
        // ★ 使用客户端 ActiveTaskCache 代替服务端 TaskManager 单例，
        //   避免专用服务器上客户端 JVM 的空 TaskManager 导致假任务前缀永不出现在多人模式。
        boolean isFakeTask = false;
        if (killer && task instanceof SRETrainTaskWrapper wrapper) {
            TaskInstance instance = wrapper.unwrap();
            if (instance != null) {
                String fakeFullId = ActiveTaskCache.getFakeTaskFullId();
                isFakeTask = fakeFullId != null && fakeFullId.equals(instance.getFullId());
            }
        }
        boolean useFakePrefix = killer && isFakeTask;

        this.text = Component.translatable("task." + (useFakePrefix ? "fake" : "feel"))
                .append(taskNameComponent);
    }
}