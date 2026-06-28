package com.habitrain.taskapi.client.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Mixin - 修改 {@link StarRailExpressTitleScreen} 主菜单界面
 *
 * 1. 版本号追加 " | Mike任务api加载中"
 * 2. 隐藏右侧更新日志面板
 * 3. 在菜单列表「退出游戏」上方添加「回放」条目
 *
 * ★ 不使用 mixin 实例字段（它们不会被注入到目标类），
 *   全部通过 @Shadow 访问目标类的字段，或使用静态变量。
 */
@Environment(EnvType.CLIENT)
@Mixin(StarRailExpressTitleScreen.class)
public class StarRailExpressTitleScreenMixin {

    @Shadow private boolean showChangelog;

    /** List<MenuEntry> */
    @Shadow private List menuEntries;

    @Shadow private int menuBaseX;
    @Shadow private int menuBaseY;
    @Shadow private float menuMaxScroll;
    @Shadow private int menuViewportTop;
    @Shadow private int menuViewportBottom;

    // ==================== 已有功能 ====================

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
                    ordinal = 0),
            index = 1)
    private static String habitrain$appendApiText(String v) {
        return v + " | Mike任务api加载中";
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$hideChangelog(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        this.showChangelog = false;
    }

    // ==================== 回放按钮（菜单列表） ====================

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$addReplayEntry(CallbackInfo ci) {
        doAddEntry();
    }

    private static final int MENU_SPACING = 26;

    /** 实际的添加逻辑，与 mixin 框架无关 */
    private void doAddEntry() {
        try {
            Constructor<?> ctor = findMenuEntryCtor();
            if (ctor == null) {
                HabiTrainTaskAPI.LOGGER.warn("[回放] 找不到 MenuEntry 构造函数");
                return;
            }

            Object entry = ctor.newInstance(
                    Component.literal("§6回放"),
                    (Runnable) () -> openReplayViewer());

            // 在「退出游戏」上方插入（即列表最后一个元素之前）
            int idx = Math.max(0, menuEntries.size() - 1);
            menuEntries.add(idx, entry);

            // 重新设置插入点及之后所有条目的坐标
            for (int i = idx; i < menuEntries.size(); i++) {
                setEntryPos(menuEntries.get(i), i, menuBaseX, menuBaseY);
            }

            // 重算滚动上限
            int totalH = menuEntries.size() * MENU_SPACING;
            int viewH = menuViewportBottom - menuViewportTop;
            menuMaxScroll = Math.max(0, totalH - viewH);

            HabiTrainTaskAPI.LOGGER.info("[回放] 已插入菜单列表（共 {} 项）", menuEntries.size());
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[回放] 添加失败", e);
        }
    }

    // ==================== 反射工具 ====================

    private static Constructor<?> findMenuEntryCtor() {
        for (Class<?> nested : StarRailExpressTitleScreen.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("MenuEntry")) {
                try {
                    Constructor<?> ctor = nested.getDeclaredConstructor(Component.class, Runnable.class);
                    ctor.setAccessible(true);
                    return ctor;
                } catch (NoSuchMethodException e) {
                    HabiTrainTaskAPI.LOGGER.error("[回放] MenuEntry 构造函数不存在", e);
                }
            }
        }
        return null;
    }

    private static void setEntryPos(Object entry, int index, int baseX, int baseY) {
        try {
            Class<?> c = entry.getClass();
            setInt(c, entry, "x", baseX);
            setInt(c, entry, "y", baseY + index * MENU_SPACING);
            setInt(c, entry, "index", index);
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[回放] 设置条目坐标失败 index={}", index, e);
        }
    }

    private static void setInt(Class<?> clz, Object obj, String name, int val) throws Exception {
        Field f = clz.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(obj, val);
    }

    private static void openReplayViewer() {
        if (!FabricLoader.getInstance().isModLoaded("replaymod")) {
            HabiTrainTaskAPI.LOGGER.warn("[回放] ReplayMod 未安装");
            return;
        }
        try {
            Class<?> rc = Class.forName("com.replaymod.replay.ReplayModReplay");
            Object mod = rc.getField("instance").get(null);
            Class<?> vc = Class.forName("com.replaymod.replay.gui.screen.GuiReplayViewer");
            Object viewer = vc.getConstructor(rc).newInstance(mod);
            vc.getMethod("display").invoke(viewer);
            HabiTrainTaskAPI.LOGGER.info("[回放] 已打开回放查看器");
        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.error("[回放] 打开失败", e);
        }
    }
}
