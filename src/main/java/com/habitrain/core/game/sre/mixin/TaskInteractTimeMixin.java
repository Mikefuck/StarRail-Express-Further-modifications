package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.virtue.TaskTimeVirtues;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 耐心/勤勉：缩放交互类任务的初始 duration（构造参数）。
 * 仅改 createTaskInstance 传给 timer 任务的 duration 常量路径 — 通过
 * {@code createTaskInstance} 返回后读取 player + task type 再改 timer 字段更稳，
 * 这里对若干 {@code <init>(I)V} 用 ModifyVariable 不可跨类，故改用
 * {@link #habitrain$scaleAfterCreate} 在 createTaskInstance RETURN 处缩放 timer。
 *
 * <p>目标交互任务：sleep / outside / read_book / exercise / meditate / bathe /
 * toilet / chair / breathe / be_alone。NoteBlock 是点击次数、Eat/Drink 无 timer，不缩放。
 */
@Mixin(SREPlayerTaskComponent.class)
public abstract class TaskInteractTimeMixin {

    private static final AtomicBoolean TIMER_FIELD_MISSING_LOGGED = new AtomicBoolean(false);

    @Shadow(remap = false)
    public abstract Player getPlayer();

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "createTaskInstance",
            at = @At("RETURN"),
            remap = false
    )
    private void habitrain$scaleAfterCreate(
            SREPlayerTaskComponent.Task taskType,
            CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        SREPlayerTaskComponent.TrainTask task = cir.getReturnValue();
        if (task == null) return;
        Player player = getPlayer();
        if (player == null) return;
        double mult = TaskTimeVirtues.interactTimeMultiplier(player);
        if (mult == 1.0) return;
        if (!isInteractiveTimedTask(task)) return;
        scaleTimerField(task, mult);
    }

    private static boolean isInteractiveTimedTask(SREPlayerTaskComponent.TrainTask task) {
        // Exclude pure wait/auto and click-count / eat / drink / scene / custom / manic.
        String name;
        try {
            name = task.getName();
        } catch (Throwable t) {
            return false;
        }
        if (name == null) return false;
        return switch (name) {
            case "sleep", "outside", "raed_book", "read_book",
                 "exercise", "meditate", "bathe", "toilet",
                 "chair", "breathe", "be_alone" -> true;
            default -> false;
        };
    }

    private static void scaleTimerField(SREPlayerTaskComponent.TrainTask task, double mult) {
        try {
            java.lang.reflect.Field f = findTimerField(task.getClass());
            if (f == null) {
                logTimerFieldMissingOnce(task.getClass());
                return;
            }
            f.setAccessible(true);
            int current = f.getInt(task);
            if (current <= 0) return;
            int scaled = Math.max(1, (int) Math.round(current * mult));
            f.setInt(task, scaled);
        } catch (Throwable t) {
            logTimerFieldMissingOnce(task.getClass());
        }
    }

    private static void logTimerFieldMissingOnce(Class<?> cls) {
        if (TIMER_FIELD_MISSING_LOGGED.compareAndSet(false, true)) {
            HabiTrainCore.LOGGER.warn(
                    "[TaskTimeVirtues] reflection cannot find timer field on {}; patience/diligence scale is no-op",
                    cls != null ? cls.getName() : "null");
        }
    }

    private static java.lang.reflect.Field findTimerField(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField("timer");
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        // ExerciseTask uses public int timer
        c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if ("timer".equals(f.getName()) && f.getType() == int.class) {
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
