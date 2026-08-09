package com.habitrain.core.client.gui;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.OptionVotePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用选项投票界面（模式/地图等字符串选项）。
 *
 * <p>视觉以列车「下一站」为主题：候选项组成横向站牌卡带，焦点卡片抬升并以
 * 暗金色点亮；下方情报栏集中展示名字、内部 id、票数占比和操作提示。服务端仍
 * 是倒计时与票数的唯一权威来源，客户端只平滑呈现收到的状态。</p>
 */
public class OptionVoteScreen extends Screen {
    // ---- 夜行列车配色 ----
    private static final int VOID = 0xFF09080B;
    private static final int INK = 0xFF151116;
    private static final int PANEL = 0xF0221917;
    private static final int PANEL_SOFT = 0xE02B211E;
    private static final int BRONZE = 0xFF624323;
    private static final int GOLD_DARK = 0xFF8E682E;
    private static final int GOLD = 0xFFD9AE59;
    private static final int GOLD_BRIGHT = 0xFFFFE1A0;
    private static final int IVORY = 0xFFF7EBCF;
    private static final int TEXT = 0xFFF4EFE6;
    private static final int TEXT_MUTED = 0xFFB8A995;
    private static final int TEXT_FAINT = 0xFF776C64;
    private static final int DANGER = 0xFFFF7169;

    private static final int CARD_MIN_W = 54;
    private static final int CARD_MAX_W = 86;
    private static final int CARD_MIN_H = 64;
    private static final int CARD_MAX_H = 142;

    private final Screen parent;
    private final long openedAtMillis = Util.getMillis();
    private final Map<String, Float> cardEmphasis = new HashMap<>();

    private List<CardHitbox> cardHitboxes = List.of();
    private Rect closeBounds = Rect.EMPTY;
    private Rect previousBounds = Rect.EMPTY;
    private Rect nextBounds = Rect.EMPTY;

    private int focusedIndex = -1;
    private String focusedOptionId = "";
    private double carouselPosition = Double.NaN;
    private float displayedCountdown = -1.0f;
    private long lastFrameMillis;

    public OptionVoteScreen(Screen parent) {
        super(OptionVoteTexts.titleFor(OptionVoteState.getVoteId()));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        syncFocus(OptionVoteState.getCandidates());
        if (Double.isNaN(carouselPosition)) {
            carouselPosition = Math.max(0, focusedIndex);
        }
        lastFrameMillis = Util.getMillis();
    }

    @Override
    public void tick() {
        super.tick();
        if (!OptionVoteState.isActive()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float frameSeconds = frameSeconds(now);
        float elapsedSeconds = (now - openedAtMillis) / 1000.0f;

        renderBackdrop(g, mouseX, mouseY, partialTick, elapsedSeconds);

        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        syncFocus(candidates);
        if (focusedIndex >= 0) {
            if (Double.isNaN(carouselPosition)) carouselPosition = focusedIndex;
            carouselPosition += (focusedIndex - carouselPosition) * approachFactor(frameSeconds, 11.0f);
        }

        renderHeader(g, mouseX, mouseY, frameSeconds, elapsedSeconds);
        OptionVotePayload.Entry focused = renderCarousel(
                g, candidates, mouseX, mouseY, frameSeconds, elapsedSeconds);
        renderDetails(g, focused, candidates.size());

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderBackdrop(GuiGraphics g, int mouseX, int mouseY,
                                float partialTick, float elapsedSeconds) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fillGradient(0, 0, width, height, 0xF708080A, 0xFB1A100E);

        // 中央暖色光晕像列车车窗，其余区域保持安静，保证文字对比度。
        int center = width / 2;
        for (int layer = 6; layer >= 1; layer--) {
            int halfWidth = Math.max(24, width * layer / 14);
            int alpha = 3 + (7 - layer) * 2;
            g.fill(center - halfWidth, 0, center + halfWidth, height,
                    withAlpha(GOLD_DARK, alpha));
        }

        int railTop = Math.max(46, height / 4);
        int railBottom = Math.max(railTop + 1, height - 58);
        g.hLine(0, width, railTop, 0x246B4B2D);
        g.hLine(0, width, railTop + 2, 0x104B3522);
        g.hLine(0, width, railBottom, 0x1F9F7238);

        // 固定种子的轻量星尘：没有随机分配，也不会随帧抖动。
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        for (int i = 0; i < 42; i++) {
            float speed = 0.18f + (i % 5) * 0.035f;
            int drift = (int) (elapsedSeconds * speed * 12.0f);
            int x = Math.floorMod(i * 83 + drift, safeWidth);
            int baseY = Math.floorMod(i * 47 + 19, safeHeight);
            int y = Mth.clamp(baseY + Math.round(Mth.sin(elapsedSeconds * speed + i) * 3.0f),
                    0, safeHeight - 1);
            float twinkle = 0.45f + 0.55f * Mth.sin(elapsedSeconds * (0.8f + i % 3 * 0.2f) + i * 0.7f);
            int alpha = Mth.clamp(18 + Math.round(Math.abs(twinkle) * 52.0f), 18, 70);
            int size = i % 13 == 0 ? 2 : 1;
            g.fill(x, y, Math.min(width, x + size), Math.min(height, y + size),
                    withAlpha(i % 4 == 0 ? GOLD_BRIGHT : IVORY, alpha));
        }

        // 由中央向外扩散的入场亮带。
        float reveal = easeOutCubic(Mth.clamp(elapsedSeconds / 0.65f, 0.0f, 1.0f));
        int revealHalf = Math.round(width * 0.5f * reveal);
        g.hLine(center - revealHalf, center + revealHalf, 46, withAlpha(GOLD, 100));
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY,
                              float frameSeconds, float elapsedSeconds) {
        String voteId = OptionVoteState.getVoteId();
        Component phase = OptionVoteTexts.phaseFor(voteId);
        g.drawString(font, phase, 12, 10, TEXT_MUTED, false);

        Component heading = OptionVoteTexts.titleFor(voteId).copy().withStyle(ChatFormatting.BOLD);
        drawScaledCentered(g, heading, width / 2.0f, 8.0f, 1.22f, IVORY);
        g.drawCenteredString(font, OptionVoteTexts.descriptionFor(voteId),
                width / 2, 27, TEXT_MUTED);

        int remaining = Math.max(0, OptionVoteState.getRemainingSeconds());
        int total = Math.max(1, OptionVoteState.getTotalSeconds());
        float targetCountdown = Mth.clamp(remaining / (float) total, 0.0f, 1.0f);
        if (displayedCountdown < 0.0f) displayedCountdown = targetCountdown;
        displayedCountdown += (targetCountdown - displayedCountdown) * approachFactor(frameSeconds, 7.0f);

        Component timer = OptionVoteState.isActive()
                ? OptionVoteTexts.timeLeft(remaining)
                : OptionVoteTexts.ended();
        int timerColor = GOLD;
        if (OptionVoteState.isActive() && remaining <= 5) {
            float pulse = 0.5f + 0.5f * Mth.sin(elapsedSeconds * 7.0f);
            timerColor = mixColor(GOLD_BRIGHT, DANGER, pulse);
        }

        closeBounds = new Rect(Math.max(0, width - 21), 7, 13, 13);
        int timerRight = closeBounds.x() - 7;
        g.drawString(font, timer, timerRight - font.width(timer), 10, timerColor, true);
        renderClose(g, mouseX, mouseY);

        int trackX = 16;
        int trackY = 43;
        int trackW = Math.max(0, width - trackX * 2);
        g.fill(trackX, trackY, trackX + trackW, trackY + 2, 0x403E322B);
        int filled = Math.round(trackW * displayedCountdown);
        g.fill(trackX, trackY, trackX + filled, trackY + 2, withAlpha(GOLD, 210));
        if (filled > 0) {
            g.fill(Math.max(trackX, trackX + filled - 5), trackY - 1,
                    trackX + filled, trackY + 3, withAlpha(GOLD_BRIGHT, 90));
        }
    }

    private OptionVotePayload.Entry renderCarousel(GuiGraphics g,
                                                    List<OptionVotePayload.Entry> candidates,
                                                    int mouseX, int mouseY,
                                                    float frameSeconds, float elapsedSeconds) {
        if (candidates.isEmpty()) {
            cardHitboxes = List.of();
            previousBounds = Rect.EMPTY;
            nextBounds = Rect.EMPTY;
            g.drawCenteredString(font, OptionVoteTexts.noCandidates(),
                    width / 2, height / 2 - 5, TEXT_MUTED);
            return null;
        }

        int detailHeight = height < 210 ? 40 : 48;
        int detailY = height - detailHeight - 6;
        int deckTop = 51;
        int deckBottom = Math.max(deckTop + CARD_MIN_H, detailY - 7);
        int deckHeight = Math.max(CARD_MIN_H, deckBottom - deckTop);
        int cardHeight = Mth.clamp(deckHeight - 4, CARD_MIN_H, CARD_MAX_H);
        int cardWidth = Mth.clamp(Math.round(cardHeight * 0.58f), CARD_MIN_W, CARD_MAX_W);
        int spacing = cardWidth + Math.max(7, cardWidth / 9);
        int baseY = deckTop + Math.max(0, (deckHeight - cardHeight) / 2) + 2;

        List<CardVisual> visuals = new ArrayList<>();
        List<CardHitbox> hitboxes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            OptionVotePayload.Entry entry = candidates.get(i);
            int baseX = Math.round((float) (width / 2.0 + (i - carouselPosition) * spacing - cardWidth / 2.0));
            if (baseX + cardWidth < -24 || baseX > width + 24) continue;

            Rect baseBounds = new Rect(baseX, baseY, cardWidth, cardHeight);
            boolean hovered = baseBounds.contains(mouseX, mouseY);
            boolean focused = i == focusedIndex;
            boolean selected = OptionVoteState.isSelected(entry.optionId());
            float target = focused ? 1.0f : (selected ? 0.78f : (hovered ? 0.58f : 0.0f));
            float emphasis = cardEmphasis.getOrDefault(entry.optionId(), 0.0f);
            emphasis += (target - emphasis) * approachFactor(frameSeconds, focused ? 13.0f : 10.0f);
            cardEmphasis.put(entry.optionId(), emphasis);

            int extraWidth = Math.round(emphasis * 6.0f);
            int extraHeight = Math.round(emphasis * 14.0f);
            int drawX = baseX - extraWidth / 2;
            int drawY = baseY - extraHeight / 2 - Math.round(emphasis * 3.0f);
            int drawW = cardWidth + extraWidth;
            int drawH = cardHeight + extraHeight;
            float distance = (float) Math.abs(i - carouselPosition);
            float visibility = Mth.clamp(1.0f - Math.max(0.0f, distance - 2.2f) * 0.26f,
                    0.22f, 1.0f);

            Rect bounds = new Rect(drawX, drawY, drawW, drawH);
            visuals.add(new CardVisual(i, entry, bounds, hovered, focused, selected,
                    emphasis, visibility));
            hitboxes.add(new CardHitbox(i, bounds));
        }

        // 从远到近绘制，焦点卡永远位于视觉最上层。
        visuals.sort(Comparator.comparingDouble(
                (CardVisual v) -> Math.abs(v.index() - carouselPosition)).reversed());
        for (CardVisual visual : visuals) {
            renderCard(g, visual, candidates.size(), elapsedSeconds);
        }
        cardHitboxes = List.copyOf(hitboxes);

        int arrowY = deckTop + deckHeight / 2 - 10;
        previousBounds = new Rect(8, arrowY, 20, 20);
        nextBounds = new Rect(Math.max(8, width - 28), arrowY, 20, 20);
        renderArrow(g, previousBounds, "<", focusedIndex > 0,
                previousBounds.contains(mouseX, mouseY));
        renderArrow(g, nextBounds, ">", focusedIndex < candidates.size() - 1,
                nextBounds.contains(mouseX, mouseY));

        return focusedIndex >= 0 && focusedIndex < candidates.size()
                ? candidates.get(focusedIndex) : null;
    }

    private void renderCard(GuiGraphics g, CardVisual visual, int candidateCount,
                            float elapsedSeconds) {
        Rect b = visual.bounds();
        int x = b.x();
        int y = b.y();
        int w = b.width();
        int h = b.height();
        int alpha = Mth.clamp(Math.round(255.0f * visual.visibility()), 50, 255);

        int border = visual.selected() ? GOLD_BRIGHT
                : (visual.focused() ? GOLD : (visual.hovered() ? GOLD_DARK : BRONZE));
        int cardTop = visual.focused() ? 0xFF4C3825 : 0xFF31251F;
        int cardBottom = visual.focused() ? 0xFF1E1718 : INK;

        fillChamfered(g, x - 3, y + 4, w + 6, h + 4, 4, withAlpha(0xFF000000, alpha * 90 / 255));
        fillChamfered(g, x, y, w, h, 4, withAlpha(border, alpha));
        fillChamfered(g, x + 1, y + 1, w - 2, h - 2, 3,
                withAlpha(PANEL_SOFT, alpha));
        g.fillGradient(x + 3, y + 5, x + w - 3, y + h - 5,
                withAlpha(cardTop, alpha), withAlpha(cardBottom, alpha));

        g.hLine(x + 7, x + w - 8, y + 5, withAlpha(border, alpha * 145 / 255));
        g.hLine(x + 7, x + w - 8, y + h - 6, withAlpha(border, alpha * 100 / 255));

        // 每张卡的金色扫描光错峰经过，形成连续但不刺眼的流动感。
        float scanPhase = (elapsedSeconds * 0.38f + visual.index() * 0.17f) % 1.0f;
        int scanY = y + 7 + Math.round(scanPhase * Math.max(1, h - 15));
        g.fill(x + 3, scanY, x + w - 3, scanY + 1,
                withAlpha(GOLD_BRIGHT, Math.round(visual.emphasis() * 72.0f * visual.visibility())));

        String ordinal = String.format(Locale.ROOT, "%02d", visual.index() + 1);
        g.drawString(font, ordinal, x + 7, y + 8,
                withAlpha(TEXT_MUTED, alpha), false);
        if (visual.selected()) {
            g.drawString(font, Component.literal("✓"), x + w - 14, y + 7,
                    withAlpha(GOLD_BRIGHT, alpha), true);
        }

        Component label = OptionVoteTexts.candidateLabel(
                visual.entry().optionId(), visual.entry().displayName());
        int medallionRadius = Mth.clamp(w / 7, 7, 12);
        int medallionX = x + w / 2;
        int medallionY = y + Math.max(25, h / 3);
        drawDiamond(g, medallionX, medallionY, medallionRadius + 3,
                withAlpha(GOLD_DARK, alpha * 150 / 255));
        drawDiamond(g, medallionX, medallionY, medallionRadius,
                withAlpha(visual.focused() ? GOLD_BRIGHT : GOLD, alpha));
        drawDiamond(g, medallionX, medallionY, Math.max(3, medallionRadius - 4),
                withAlpha(INK, alpha));

        String labelString = label.getString().trim();
        if (!labelString.isEmpty()) {
            int codePoint = labelString.codePointAt(0);
            Component sigil = Component.literal(new String(Character.toChars(codePoint)))
                    .withStyle(ChatFormatting.BOLD);
            g.drawCenteredString(font, sigil, medallionX, medallionY - 4,
                    withAlpha(IVORY, alpha));
        }

        int footerY = y + h - 16;
        int maxLabelWidth = Math.max(24, w - 12);
        List<FormattedCharSequence> lines = font.split(label, maxLabelWidth);
        int lineCount = Math.min(2, lines.size());
        int labelY = Math.max(medallionY + medallionRadius + 5, footerY - 4 - lineCount * 9);
        for (int line = 0; line < lineCount; line++) {
            FormattedCharSequence textLine = lines.get(line);
            g.drawCenteredString(font, textLine, x + w / 2, labelY + line * 9,
                    withAlpha(TEXT, alpha));
        }

        Component votes = OptionVoteTexts.compactVotes(visual.entry().votes());
        g.drawCenteredString(font, votes, x + w / 2, footerY,
                withAlpha(visual.selected() ? GOLD_BRIGHT : TEXT_MUTED, alpha));

        if (visual.focused()) {
            int markerX = x + w / 2;
            drawDiamond(g, markerX, y + h + 5, 3, withAlpha(GOLD_BRIGHT, alpha));
        }
    }

    private void renderDetails(GuiGraphics g, OptionVotePayload.Entry focused, int candidateCount) {
        int panelHeight = height < 210 ? 40 : 48;
        int panelX = 12;
        int panelY = height - panelHeight - 6;
        int panelW = Math.max(0, width - 24);
        if (panelW <= 0) return;

        g.fillGradient(panelX, panelY, panelX + panelW, panelY + panelHeight,
                0xE6241B1A, 0xF00E0C0F);
        g.renderOutline(panelX, panelY, panelW, panelHeight, 0x8A76512B);
        g.fill(panelX + 1, panelY + 1, panelX + 3, panelY + panelHeight - 1, GOLD_DARK);

        if (focused == null) return;

        Component label = OptionVoteTexts.candidateLabel(focused.optionId(), focused.displayName());
        int rightReserve = width < 360 ? 90 : 130;
        Component fittedName = fit(label, Math.max(36, panelW - rightReserve - 20));
        g.drawString(font, fittedName, panelX + 10, panelY + 7, IVORY, true);

        Component progress = OptionVoteTexts.candidateProgress(focusedIndex + 1, candidateCount);
        g.drawString(font, progress, panelX + 10, panelY + 20, TEXT_MUTED, false);
        if (width >= 390) {
            Component optionId = OptionVoteTexts.optionId(shortOptionId(focused.optionId()));
            g.drawString(font, fit(optionId, Math.max(30, panelW / 2 - 66)),
                    panelX + 62, panelY + 20, TEXT_FAINT, false);
        }

        int totalVotes = OptionVoteState.getCandidates().stream()
                .mapToInt(OptionVotePayload.Entry::votes).sum();
        int percent = totalVotes <= 0 ? 0 : Math.round(focused.votes() * 100.0f / totalVotes);
        Component share = OptionVoteTexts.voteShare(focused.votes(), percent);
        g.drawString(font, share, panelX + panelW - 10 - font.width(share),
                panelY + 7, GOLD, true);

        boolean selected = OptionVoteState.isSelected(focused.optionId());
        Component status = selected ? OptionVoteTexts.selectedStatus() : OptionVoteTexts.availableStatus();
        int statusColor = selected ? GOLD_BRIGHT : TEXT_MUTED;
        g.drawString(font, status, panelX + panelW - 10 - font.width(status),
                panelY + 20, statusColor, false);

        if (panelHeight >= 48) {
            Component hint = fit(OptionVoteTexts.controlHint(), Math.max(30, panelW - 20));
            g.drawCenteredString(font, hint, width / 2, panelY + 34, TEXT_FAINT);
        }
    }

    private void renderClose(GuiGraphics g, int mouseX, int mouseY) {
        boolean hovered = closeBounds.contains(mouseX, mouseY);
        int border = hovered ? GOLD_BRIGHT : BRONZE;
        g.fill(closeBounds.x(), closeBounds.y(), closeBounds.right(), closeBounds.bottom(),
                hovered ? 0xA34B3424 : 0x70211818);
        g.renderOutline(closeBounds.x(), closeBounds.y(), closeBounds.width(), closeBounds.height(), border);
        g.drawCenteredString(font, Component.literal("×"),
                closeBounds.x() + closeBounds.width() / 2,
                closeBounds.y() + 2, hovered ? IVORY : TEXT_MUTED);
    }

    private void renderArrow(GuiGraphics g, Rect bounds, String glyph,
                             boolean enabled, boolean hovered) {
        int border = enabled ? (hovered ? GOLD_BRIGHT : GOLD_DARK) : 0xFF3B302C;
        int background = enabled ? (hovered ? 0xB34A3424 : 0x8A211A18) : 0x6A121013;
        g.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), background);
        g.renderOutline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), border);
        g.drawCenteredString(font, Component.literal(glyph),
                bounds.x() + bounds.width() / 2, bounds.y() + 6,
                enabled ? (hovered ? IVORY : GOLD) : TEXT_FAINT);
    }

    private void syncFocus(List<OptionVotePayload.Entry> candidates) {
        if (candidates.isEmpty()) {
            focusedIndex = -1;
            focusedOptionId = "";
            carouselPosition = Double.NaN;
            return;
        }

        if (!focusedOptionId.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (focusedOptionId.equals(candidates.get(i).optionId())) {
                    focusedIndex = i;
                    return;
                }
            }
        }

        for (int i = 0; i < candidates.size(); i++) {
            if (OptionVoteState.isSelected(candidates.get(i).optionId())) {
                setFocus(i, candidates, false);
                return;
            }
        }

        // 初次打开时将中间候选放在车窗中央，最大化可见候选数量。
        setFocus(candidates.size() / 2, candidates, false);
    }

    private void changeFocus(int delta) {
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        if (candidates.isEmpty()) return;
        int next = Mth.clamp(focusedIndex + delta, 0, candidates.size() - 1);
        if (next != focusedIndex) {
            setFocus(next, candidates, true);
        }
    }

    private void setFocus(int index, List<OptionVotePayload.Entry> candidates, boolean sound) {
        if (candidates.isEmpty()) return;
        int clamped = Mth.clamp(index, 0, candidates.size() - 1);
        boolean changed = focusedIndex != clamped;
        focusedIndex = clamped;
        focusedOptionId = candidates.get(clamped).optionId();
        if (sound && changed) playUiSound(1.25f);
    }

    private void castVote(int index) {
        if (!OptionVoteState.isActive()) return;
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        if (index < 0 || index >= candidates.size()) return;

        OptionVotePayload.Entry entry = candidates.get(index);
        setFocus(index, candidates, false);
        boolean wasSelected = OptionVoteState.isSelected(entry.optionId());
        OptionVoteState.toggleSelection(entry.optionId());
        if (OptionVoteState.isSelected(entry.optionId())) {
            PayloadSenders.sendOptionVoteCast(OptionVoteState.getVoteId(), entry.optionId());
            playUiSound(1.05f);
        } else if (wasSelected) {
            PayloadSenders.sendOptionVoteCast(OptionVoteState.getVoteId(), null);
            playUiSound(0.82f);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (closeBounds.contains(mouseX, mouseY)) {
                onClose();
                return true;
            }
            if (previousBounds.contains(mouseX, mouseY) && focusedIndex > 0) {
                changeFocus(-1);
                return true;
            }
            List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
            if (nextBounds.contains(mouseX, mouseY) && focusedIndex < candidates.size() - 1) {
                changeFocus(1);
                return true;
            }
            if (OptionVoteState.isActive()) {
                // 与绘制层级相反命中，保证重叠时优先选择最上层卡片。
                for (int i = cardHitboxes.size() - 1; i >= 0; i--) {
                    CardHitbox hitbox = cardHitboxes.get(i);
                    if (hitbox.bounds().contains(mouseX, mouseY)) {
                        castVote(hitbox.index());
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (OptionVoteState.isActive() && scrollY != 0.0) {
            changeFocus(scrollY > 0.0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP
                || keyCode == GLFW.GLFW_KEY_A) {
            changeFocus(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_D) {
            changeFocus(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            castVote(focusedIndex);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        // 避免模式→地图自动重建时把多个投票界面叠成 parent 链。
        Screen next = parent;
        while (next instanceof OptionVoteScreen nested) {
            next = nested.parent;
        }
        mc.setScreen(next);
    }

    /** Parent screen for auto-open rebuild / close chain. */
    public Screen getParentScreen() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private float frameSeconds(long now) {
        if (lastFrameMillis <= 0L) {
            lastFrameMillis = now;
            return 1.0f / 60.0f;
        }
        float seconds = Mth.clamp((now - lastFrameMillis) / 1000.0f, 0.0f, 0.1f);
        lastFrameMillis = now;
        return seconds;
    }

    private void playUiSound(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private Component fit(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String plain = text.getString();
        String suffix = "…";
        int bodyWidth = Math.max(0, maxWidth - font.width(suffix));
        return Component.literal(font.plainSubstrByWidth(plain, bodyWidth) + suffix)
                .withStyle(text.getStyle());
    }

    private static String shortOptionId(String optionId) {
        if (optionId == null) return "";
        int split = optionId.lastIndexOf(':');
        return split >= 0 && split + 1 < optionId.length()
                ? optionId.substring(split + 1) : optionId;
    }

    private void drawScaledCentered(GuiGraphics g, Component text, float x, float y,
                                    float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawCenteredString(font, text, 0, 0, color);
        g.pose().popPose();
    }

    private static void fillChamfered(GuiGraphics g, int x, int y, int width, int height,
                                      int cut, int color) {
        if (width <= 0 || height <= 0) return;
        int safeCut = Math.min(Math.max(0, cut), Math.min(width, height) / 2);
        g.fill(x + safeCut, y, x + width - safeCut, y + height, color);
        g.fill(x + 1, y + Math.max(1, safeCut / 2),
                x + width - 1, y + height - Math.max(1, safeCut / 2), color);
        g.fill(x, y + safeCut, x + width, y + height - safeCut, color);
    }

    private static void drawDiamond(GuiGraphics g, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int halfWidth = radius - Math.abs(dy);
            g.fill(centerX - halfWidth, centerY + dy,
                    centerX + halfWidth + 1, centerY + dy + 1, color);
        }
    }

    private static float approachFactor(float seconds, float speed) {
        return 1.0f - (float) Math.exp(-Math.max(0.0f, seconds) * speed);
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse;
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int mixColor(int from, int to, float amount) {
        float t = Mth.clamp(amount, 0.0f, 1.0f);
        int a = Math.round(Mth.lerp(t, (from >>> 24) & 0xFF, (to >>> 24) & 0xFF));
        int r = Math.round(Mth.lerp(t, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return a << 24 | r << 16 | g << 8 | b;
    }

    private record Rect(int x, int y, int width, int height) {
        private static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return width > 0 && height > 0
                    && mouseX >= x && mouseX < right()
                    && mouseY >= y && mouseY < bottom();
        }
    }

    private record CardHitbox(int index, Rect bounds) {}

    private record CardVisual(int index,
                              OptionVotePayload.Entry entry,
                              Rect bounds,
                              boolean hovered,
                              boolean focused,
                              boolean selected,
                              float emphasis,
                              float visibility) {}
}
