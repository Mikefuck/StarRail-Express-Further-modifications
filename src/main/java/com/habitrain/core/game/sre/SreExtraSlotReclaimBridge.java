package com.habitrain.core.game.sre;

import com.habitrain.core.api.spi.ExtraSlotReclaimBridge;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * {@link ExtraSlotReclaimBridge} 的上游组件实现。
 *
 * <p>把 {@code io.wifi.starrailexpress.cca.ExtraSlotComponent} 的编译期依赖从
 * {@code com.habitrain.core.api.ItemReclaimHelper} 移到这里（模式层），
 * 公开层因此不再直接依赖上游实现类。
 */
public final class SreExtraSlotReclaimBridge implements ExtraSlotReclaimBridge {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("habitrain_core|ExtraSlotReclaim");

    /** 审核 M-18：链接失败的 error 级日志只打一次，避免每 tick 刷屏。 */
    private static final AtomicBoolean LINKAGE_LOGGED = new AtomicBoolean();

    public static final SreExtraSlotReclaimBridge INSTANCE = new SreExtraSlotReclaimBridge();

    private SreExtraSlotReclaimBridge() {}

    @Override
    public boolean reclaimMatching(Player player, Predicate<ItemStack> match) {
        if (player == null || match == null) return false;
        try {
            var optional = ExtraSlotComponent.KEY.maybeGet(player);
            if (optional.isEmpty()) return false;
            ExtraSlotComponent extra = optional.get();
            if (extra.SLOTS == null || extra.SLOTS.isEmpty()) return false;

            List<ResourceLocation> toRemove = new ArrayList<>();
            for (var entry : extra.SLOTS.entrySet()) {
                if (match.test(entry.getValue())) {
                    toRemove.add(entry.getKey());
                }
            }
            for (ResourceLocation slot : toRemove) {
                extra.removeSlot(slot);
            }
            return !toRemove.isEmpty();
        } catch (LinkageError e) {
            // 审核 M-18：上游重命名 extra.SLOTS 等字段时，旧实现静默 return false，
            // 表现为「任务取消了道具仍在额外槽位」。这类链接失败只报一次 error。
            if (LINKAGE_LOGGED.compareAndSet(false, true)) {
                LOGGER.error("上游 ExtraSlotComponent 契约变化，额外槽位回收已静默失效", e);
            }
            return false;
        } catch (Throwable t) {
            LOGGER.warn("额外槽位回收失败", t);
            return false;
        }
    }
}
