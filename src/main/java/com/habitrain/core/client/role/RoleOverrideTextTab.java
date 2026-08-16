package com.habitrain.core.client.role;

import com.habitrain.core.api.role.book.RoleBookPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.agmas.noellesroles.client.screen.RoleIntroduceScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Client adapter from the public, server-safe role-book page model to SRE's
 * current role introduction screen tab contract.
 */
public final class RoleOverrideTextTab implements RoleIntroduceScreen.DetailTab {
    private static final int PARAGRAPH_GAP = 5;

    private final RoleBookPage page;
    private final Font font;
    private int scrollOffset;
    private int maxScroll;
    private int contentHeight;
    private int cachedWidth = -1;
    private List<Line> cachedLines = List.of();

    public RoleOverrideTextTab(RoleBookPage page) {
        this.page = page;
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public Component getTitle() {
        return page.title();
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int mouseX,
            int mouseY,
            float delta) {
        prepareLines(Math.max(1, width));
        maxScroll = Math.max(0, contentHeight - height);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        graphics.enableScissor(x, y, x + width, y + height);
        int drawY = y - scrollOffset;
        for (Line line : cachedLines) {
            if (drawY + font.lineHeight >= y && drawY <= y + height) {
                graphics.drawString(font, line.text(), x, drawY, 0xFFFFFFFF);
            }
            drawY += line.height();
        }
        graphics.disableScissor();
    }

    private void prepareLines(int width) {
        if (cachedWidth == width) return;

        List<Line> lines = new ArrayList<>();
        List<Component> paragraphs = page.paragraphs();
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.size(); paragraphIndex++) {
            for (FormattedCharSequence line : font.split(paragraphs.get(paragraphIndex), width)) {
                lines.add(new Line(line, font.lineHeight));
            }
            if (paragraphIndex + 1 < paragraphs.size()) {
                lines.add(new Line(FormattedCharSequence.EMPTY, PARAGRAPH_GAP));
            }
        }

        cachedWidth = width;
        cachedLines = List.copyOf(lines);
        contentHeight = lines.stream().mapToInt(Line::height).sum();
    }

    @Override
    public int getScrollOffset() {
        return scrollOffset;
    }

    @Override
    public int getMaxScroll() {
        return maxScroll;
    }

    @Override
    public void setScrollOffset(int value) {
        scrollOffset = Mth.clamp(value, 0, maxScroll);
    }

    @Override
    public int getContentHeight() {
        return contentHeight;
    }

    @Override
    public void onSwitchTo() {
        scrollOffset = 0;
    }

    private record Line(FormattedCharSequence text, int height) {}
}
