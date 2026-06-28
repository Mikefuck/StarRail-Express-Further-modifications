package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin - 修正 SRE 的 TaskRenderer 对 CUSTOM 类型任务的显示文本。
 *
 * SRE 的 HudMoodRenderer$TaskRenderer 和 MoodRenderer$TaskRenderer
 * 在渲染所有任务时都会添加 "task." + (feel/fake) 前缀。
 * 对于 CUSTOM 类型的 DLC 任务，我们不想要这个前缀——应该直接显示任务名称。
 */
@Mixin(targets = {
    "io.wifi.starrailexpress.client.gui.HudMoodRenderer$TaskRenderer",
    "io.wifi.starrailexpress.client.gui.MoodRenderer$TaskRenderer"
})
public class FixTaskRendererMixin {

    @Shadow(remap = false)
    private Component text;

    /**
     * 在 tick() 末尾检查 CUSTOM 类型任务，覆盖 text 字段去掉前缀。
     */
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void habitrain$fixCustomTaskText(SREPlayerTaskComponent.TrainTask task, float delta,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (task != null && task.getType() == SREPlayerTaskComponent.Task.CUSTOM) {
            // 直接使用任务名称，去掉 SRE 加的 "task.feel" / "task.fake" 前缀
            this.text = Component.literal(task.getName());
        }
    }
}
