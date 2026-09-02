package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.render.CustomTitlePanoramaRenderer;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 * 3. 使用自定义主页背景（可选）
 * 4. 移除 Discord 菜单项、替换 QQ 群链接并添加列车网站入口
 *
 * ★ 不使用 mixin 实例字段（它们不会被注入到目标类），
 *   全部通过 @Shadow 访问目标类的字段，或使用静态变量。
 */
@Environment(EnvType.CLIENT)
@Mixin(value = StarRailExpressTitleScreen.class, remap = false)
public class StarRailExpressTitleScreenMixin {

    @Shadow private boolean showChangelog;
    @Shadow private float panoramaFade;

    /** List<MenuEntry> */
    @Shadow private List menuEntries;

    @Shadow private int menuBaseX;
    @Shadow private int menuBaseY;
    @Shadow private float menuMaxScroll;
    @Shadow private int menuViewportTop;
    @Shadow private int menuViewportBottom;

    @Unique
    private static final int HABITRAIN$MENU_SPACING = 26;
    @Unique
    private static final String HABITRAIN$QQ_GROUP_URL = "https://qm.qq.com/q/SeuOMVpSk8";
    @Unique
    private static final String HABITRAIN$TRAIN_WEBSITE_URL = "https://www.kgpaly.cloud/train/";
    @Unique
    private static final Component HABITRAIN$QQ_ENTRY = Component.translatable("menu.sre.join_qq");
    @Unique
    private static final Component HABITRAIN$TRAIN_WEBSITE_ENTRY =
            Component.translatable("menu.habitrain_core.train_website");
    @Unique
    private static final Component HABITRAIN$DISCORD_ENTRY = Component.translatable("menu.sre.join_discord");

    // ==================== 已有功能 ====================

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
                    ordinal = 0),
            index = 1)
    private static String habitrain$appendApiText(String v) {
        return v + " | 哈比列车api加载中";
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$hideChangelog(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        this.showChangelog = false;
    }

    /**
     * Runs after SRE's waiting/continue black-screen branch but before it chooses its video
     * frames or bundled cube map. This preserves the loading gate while replacing only the
     * visible rotating background.
     */
    @Inject(
            method = "renderPanorama",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/SREClientConfig;instance()Lio/wifi/starrailexpress/SREClientConfig;",
                    shift = At.Shift.BEFORE),
            cancellable = true,
            require = 1)
    private void habitrain$renderCustomPanorama(GuiGraphics graphics, float delta, CallbackInfo ci) {
        if (!ClientVisualPreferences.isCustomTitlePanoramaEnabled()) return;

        StarRailExpressTitleScreen screen = (StarRailExpressTitleScreen) (Object) this;
        CustomTitlePanoramaRenderer.render(
                graphics, screen.width, screen.height, this.panoramaFade, delta);
        ci.cancel();
    }

    // ==================== 社群入口（菜单列表） ====================

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$customizeCommunityEntries(CallbackInfo ci) {
        habitrain$customizeCommunityEntries();
    }

    @Unique
    private void habitrain$customizeCommunityEntries() {
        try {
            Constructor<?> ctor = habitrain$findMenuEntryCtor();
            if (ctor == null) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 找不到 MenuEntry 构造函数");
                return;
            }

            Field textField = ctor.getDeclaringClass().getDeclaredField("text");
            textField.setAccessible(true);
            boolean replacedQq = false;
            boolean removedDiscord = false;
            boolean websitePresent = false;
            int qqEntryIndex = -1;

            for (int i = 0; i < menuEntries.size(); i++) {
                Object entry = menuEntries.get(i);
                Component text = (Component) textField.get(entry);

                if (HABITRAIN$DISCORD_ENTRY.equals(text)) {
                    menuEntries.remove(i--);
                    removedDiscord = true;
                    continue;
                }
                if (HABITRAIN$TRAIN_WEBSITE_ENTRY.equals(text)) {
                    websitePresent = true;
                    continue;
                }
                if (HABITRAIN$QQ_ENTRY.equals(text)) {
                    menuEntries.set(i, ctor.newInstance(
                            text,
                            (Runnable) () -> Util.getPlatform().openUri(HABITRAIN$QQ_GROUP_URL)));
                    replacedQq = true;
                    qqEntryIndex = i;
                }
            }

            if (qqEntryIndex >= 0 && !websitePresent) {
                menuEntries.add(qqEntryIndex + 1, ctor.newInstance(
                        HABITRAIN$TRAIN_WEBSITE_ENTRY,
                        (Runnable) () -> Util.getPlatform().openUri(HABITRAIN$TRAIN_WEBSITE_URL)));
            }

            for (int i = 0; i < menuEntries.size(); i++) {
                habitrain$setEntryPos(menuEntries.get(i), i, menuBaseX, menuBaseY);
            }

            int totalH = menuEntries.size() * HABITRAIN$MENU_SPACING;
            int viewH = menuViewportBottom - menuViewportTop;
            menuMaxScroll = Math.max(0, totalH - viewH);

            if (!replacedQq) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 未找到 QQ 群菜单项，链接未替换且网站入口未添加");
            }
            if (!removedDiscord) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 未找到 Discord 菜单项");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[主页菜单] 修改社群入口失败", e);
        }
    }

    // ==================== 反射工具 ====================

    @Unique
    private static Constructor<?> habitrain$findMenuEntryCtor() {
        for (Class<?> nested : StarRailExpressTitleScreen.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("MenuEntry")) {
                try {
                    Constructor<?> ctor = nested.getDeclaredConstructor(Component.class, Runnable.class);
                    ctor.setAccessible(true);
                    return ctor;
                } catch (NoSuchMethodException e) {
                    HabiTrainCore.LOGGER.error("[主页菜单] MenuEntry 构造函数不存在", e);
                }
            }
        }
        return null;
    }

    @Unique
    private static void habitrain$setEntryPos(Object entry, int index, int baseX, int baseY) {
        try {
            Class<?> c = entry.getClass();
            habitrain$setInt(c, entry, "x", baseX);
            habitrain$setInt(c, entry, "y", baseY + index * HABITRAIN$MENU_SPACING);
            habitrain$setInt(c, entry, "index", index);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[主页菜单] 设置条目坐标失败 index={}", index, e);
        }
    }

    @Unique
    private static void habitrain$setInt(Class<?> clz, Object obj, String name, int val) throws Exception {
        Field f = clz.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(obj, val);
    }
}
