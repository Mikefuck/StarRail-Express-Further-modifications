package com.habitrain.core.client.gui.config;

import net.minecraft.client.gui.Font;

/**
 * 页面 → ConfigRootScreen 的访问接口。页面只依赖此接口，不依赖具体根实现，
 * 从而可在根重写之前独立编译。
 */
public interface ConfigContext {

    Font font();

    /** 联机服务器中仅 OP 可修改（沿用 LiveConfigAccess.canEditRemoteConfigs）。 */
    boolean editable();

    /** 是否有未落盘修改（透传 ConfigManager.isDirty()）。 */
    boolean isDirty();

    /** 标记有修改（写入配置层 dirty 标记）。等价 ConfigManager.getInstance().markEnvironmentDirty()。 */
    void markDirty();

    /** 立即落盘 + 同步服务器 + 清 dirty（等价旧 saveConfigNow / ConfigManager.save()）。 */
    void saveNow();

    /** 压入子页面（任务详情等），根切换到栈顶并显示面包屑。 */
    void pushPage(ConfigPage page);

    /** 出栈返回上一页。 */
    void popPage();

    /** 返回：栈非空则 popPage，否则关闭面板回 ModMenu。 */
    void requestBack();

    /** 顶部 toast 提示。 */
    void toast(String message);
}
