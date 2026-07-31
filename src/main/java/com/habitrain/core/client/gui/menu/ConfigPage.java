package com.habitrain.core.client.gui.menu;

import net.minecraft.client.gui.GuiGraphics;

/** 配置中心子页接口。x,y,w,h 为内容区（不含顶部 Tab / 子 Tab / 底部保存栏）。 */
public interface ConfigPage {
    /** 是否有可修改选项（“其他”空态页返回 false → 不显示保存栏）。 */
    boolean canSave();
    /** 提交页面级待处理状态到配置模型（即时持久化；多数页面 no-op）。 */
    void save();
    /** 把聚焦/可编辑文本框写入配置模型（保存/切页/关闭前调用）。 */
    void flushPending();

    void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h);
    boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h);
    boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h);
    boolean mouseReleased(double mx, double my, int btn);
    boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h);
    boolean keyPressed(int key, int scan, int mod);
    boolean charTyped(char ch, int mod);
}
