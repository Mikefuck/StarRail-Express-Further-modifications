package com.habitrain.core.client.gui.menu.ui;

import net.minecraft.client.gui.components.EditBox;

import java.util.List;

/**
 * 为配置子页中手动绘制的文本框统一转发输入事件。
 *
 * <p><strong>维护警告：</strong>这些文本框不是 {@code Screen} 的 drawable child，
 * 仅调用 {@code render} 会得到一个“看得见但不能输入”的文本框。新增字段时必须把它
 * 加入本路由器，确保点击聚焦、按键和字符输入始终成套转发。
 */
public final class EditBoxInputRouter {
    private final List<EditBox> fields;

    public EditBoxInputRouter(EditBox... fields) {
        this.fields = List.of(fields);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (EditBox field : fields) {
            if (field.mouseClicked(mouseX, mouseY, button)) {
                focusOnly(field);
                return true;
            }
        }
        clearFocus();
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox field : fields) {
            if (field.isFocused() && field.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox field : fields) {
            if (field.isFocused() && field.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public void clearFocus() {
        for (EditBox field : fields) {
            field.setFocused(false);
        }
    }

    private void focusOnly(EditBox selected) {
        for (EditBox field : fields) {
            field.setFocused(field == selected);
        }
    }
}
