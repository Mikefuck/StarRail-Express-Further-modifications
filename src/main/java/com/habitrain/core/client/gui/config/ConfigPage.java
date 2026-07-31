package com.habitrain.core.client.gui.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 配置页统一契约。所有 ModMenu 配置页面实现此接口；
 * 由 ConfigRootScreen 负责事件分发、页面栈与底部保存栏。
 */
public interface ConfigPage {

    /** 页面标题（顶部面包屑与左侧导航使用）。 */
    Component title();

    /** 进入本页（首次选中/出栈后返回）。 */
    default void onEnter() {}

    /** 离开本页（切页/出栈/关闭面板）。用于刷未提交输入，等价旧 flushPendingFields/flushFocusedFields。 */
    default void onLeave() {}

    /** 根界面按下底部「保存」时调用。有未提交文本字段的页面在此提交，随后根调用 ConfigManager.save()。 */
    default void onSaveRequested() {}

    void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h);

    default boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    default boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return false; }
    default boolean mouseReleased(double mx, double my, int btn, int x, int y, int w, int h) { return false; }
    default boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return false; }
    default boolean keyPressed(int key, int scan, int mod) { return false; }
    default boolean charTyped(char ch, int mod) { return false; }
}
