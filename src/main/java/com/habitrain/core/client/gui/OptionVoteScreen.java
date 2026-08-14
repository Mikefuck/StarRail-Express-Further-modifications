package com.habitrain.core.client.gui;

import com.habitrain.core.client.BlackoutKeyHandler;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    private static final int CARD_MIN_W = 62;
    private static final int CARD_MAX_W = 112;
    private static final int CARD_MIN_H = 68;
    private static final int CARD_MAX_H = 142;

    // ---- 地图档案卡（仅 map 阶段） ----
    private static final long DETAIL_OPEN_MILLIS = 220L;      // 档案卡滑入时长
    private static final long DETAIL_CLOSE_MILLIS = 180L;     // 档案卡收起时长
    private static final long DETAIL_SWITCH_MILLIS = 140L;    // 切换地图时交叉淡入时长
    private static final float DECK_CENTER_RATIO = 0.29f;      // 档案展开后，候选卡组位于左半区
    private static final float DETAIL_CENTER_RATIO = 0.76f;    // 档案卡中心位于右半区
    private static final float DETAIL_W_RATIO = 0.34f;         // 档案卡承担右半区的主要视觉重量
    private static final int DETAIL_MIN_W = 158;
    private static final int DETAIL_MAX_W = 520;
    private static final int DETAIL_MIN_H = 190;
    private static final int DETAIL_MAX_H = 520;
    private static final int LAYOUT_MARGIN = 24;              // 左右边距
    private static final int LAYOUT_GAP = 28;                 // 卡组与档案卡最小间隙
    private static final int DETAIL_PAD = 14;
    private static final int PREVIEW_ASPECT_W = 16;
    private static final int PREVIEW_ASPECT_H = 9;

    private final Screen parent;
    private final long openedAtMillis = Util.getMillis();
    private final Map<String, Float> cardEmphasis = new HashMap<>();
    private final List<CardVisual> visualBuffer = new ArrayList<>();
    private final List<CardHitbox> hitboxBuffer = new ArrayList<>();
    private final Map<String, List<FormattedCharSequence>> wrappedTextCache = new HashMap<>();

    private List<CardHitbox> cardHitboxes = List.of();
    private Rect closeBounds = Rect.EMPTY;
    private Rect previousBounds = Rect.EMPTY;
    private Rect nextBounds = Rect.EMPTY;
    private Rect detailBounds = Rect.EMPTY;

    private int focusedIndex = -1;
    private String focusedOptionId = "";
    private double carouselPosition = Double.NaN;
    private float displayedCountdown = -1.0f;
    private long lastFrameMillis;

    // ---- 地图档案卡动画状态 ----
    private boolean mapPhase;
    private boolean detailRequested;
    private float detailProgress = 0.0f;        // 0..1 线性计时，easeOutCubic 后驱动位移/alpha
    private boolean detailOpenTarget;
    private float detailSwitch = 1.0f;           // 1 = 无切换进行中；0→1 交叉淡入
    private String detailShowingMapId = "";
    private String oldShowingMapId = "";
    private MapVoteProfilePayload.MapProfile oldProfile;
    private float detailScroll;
    private float detailScrollTarget;
    private float detailScrollMax;
    private int mapPaneSplitX = Integer.MAX_VALUE;

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
        detailRequested = "map".equals(OptionVoteState.getVoteId())
                && selectedOptionId() != null;
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

        updateDetailAnimation(frameSeconds, candidates);

        renderHeader(g, mouseX, mouseY, frameSeconds, elapsedSeconds);
        OptionVotePayload.Entry focused = renderCarousel(
                g, candidates, mouseX, mouseY, frameSeconds, elapsedSeconds);
        renderDetails(g, focused, candidates.size(), elapsedSeconds);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 每帧推进档案卡开/关与切换动画。 */
    private void updateDetailAnimation(float frameSeconds, List<OptionVotePayload.Entry> candidates) {
        mapPhase = "map".equals(OptionVoteState.getVoteId()) && !candidates.isEmpty();

        // 地图投票先展示完整候选卡组；玩家明确投票后，卡组才整体左移并让出档案区。
        boolean target = mapPhase && detailRequested;
        if (target != detailOpenTarget) {
            detailOpenTarget = target;
        }
        if (detailOpenTarget && detailProgress < 1.0f) {
            detailProgress = Math.min(1.0f, detailProgress + frameSeconds / (DETAIL_OPEN_MILLIS / 1000.0f));
        } else if (!detailOpenTarget && detailProgress > 0.0f) {
            detailProgress = Math.max(0.0f, detailProgress - frameSeconds / (DETAIL_CLOSE_MILLIS / 1000.0f));
        }

        // 切换地图：只交叉淡入档案卡内容，不收起再展开
        if (mapPhase && !detailShowingMapId.equals(focusedOptionId)) {
            oldShowingMapId = detailShowingMapId;
            oldProfile = OptionVoteState.getProfile(oldShowingMapId);
            detailShowingMapId = focusedOptionId;
            detailSwitch = 0.0f;
            detailScroll = 0.0f;
            detailScrollTarget = 0.0f;
        }
        if (detailSwitch < 1.0f) {
            detailSwitch = Math.min(1.0f, detailSwitch + frameSeconds / (DETAIL_SWITCH_MILLIS / 1000.0f));
        }
        detailScrollTarget = Mth.clamp(detailScrollTarget, 0.0f, detailScrollMax);
        detailScroll += (detailScrollTarget - detailScroll) * approachFactor(frameSeconds, 14.0f);
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
        // 倒计时三态：>5s 暗金；≤5s 橙红；≤3s 数字透明度脉动
        int timerColor = GOLD;
        int timerAlpha = 255;
        if (OptionVoteState.isActive() && remaining <= 5) {
            timerColor = mixColor(GOLD_BRIGHT, DANGER, 0.80f);
        }
        if (OptionVoteState.isActive() && remaining <= 3) {
            float pulse = 0.5f + 0.5f * Mth.sin(elapsedSeconds * 7.0f);
            timerAlpha = Math.round(255 * (0.72f + 0.28f * pulse));
        }

        closeBounds = new Rect(Math.max(0, width - 21), 7, 13, 13);
        // 右上角： [剩余时间]  按 ESC / <已注册键> 隐藏  [×]
        Component hideTip = OptionVoteTexts.hideHintWithBoundKey();
        int hideTipRight = closeBounds.x() - 7;
        int hideTipX = hideTipRight - font.width(hideTip);
        g.drawString(font, hideTip, hideTipX, 10, TEXT_MUTED, false);
        int timerRight = hideTipX - 7;
        g.drawString(font, timer, timerRight - font.width(timer), 10,
                withAlpha(timerColor, timerAlpha), true);
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

        DeckMetrics metrics = deckMetrics();
        int cardWidth = metrics.cardWidth();
        int cardHeight = metrics.cardHeight();
        int spacing = metrics.spacing();
        int baseY = metrics.baseY();

        List<CardVisual> visuals = visualBuffer;
        visuals.clear();
        MapLayout layout = computeMapLayout();
        float t = easeOutCubic(Mth.clamp(detailProgress, 0.0f, 1.0f));
        int deckCenterX = Math.round(Mth.lerp(t, width / 2.0f, layout.deckCenterX()));
        int listRight = Math.round(Mth.lerp(t, width, layout.listRight()));
        for (int i = 0; i < candidates.size(); i++) {
            OptionVotePayload.Entry entry = candidates.get(i);
            int baseX = Math.round((float) (deckCenterX + (i - carouselPosition) * spacing - cardWidth / 2.0));
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
        }

        // 从远到近绘制，焦点卡永远位于视觉最上层。
        visuals.sort(Comparator.comparingDouble(
                (CardVisual v) -> Math.abs(v.index() - carouselPosition)).reversed());
        if (t > 0.001f) {
            g.enableScissor(0, metrics.deckTop() - 14, Math.max(1, listRight),
                    metrics.deckTop() + metrics.deckHeight() + 18);
        }
        List<CardHitbox> hitboxes = hitboxBuffer;
        hitboxes.clear();
        for (CardVisual visual : visuals) {
            renderCard(g, visual, candidates.size(), elapsedSeconds);
            hitboxes.add(new CardHitbox(visual.index(), visual.bounds()));
        }
        if (t > 0.001f) {
            g.disableScissor();
        }
        cardHitboxes = hitboxes;

        int arrowY = metrics.deckTop() + metrics.deckHeight() / 2 - 10;
        previousBounds = new Rect(8, arrowY, 20, 20);
        nextBounds = new Rect(Math.max(8, listRight - 28), arrowY, 20, 20);
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

        // 不再用地图名首字充当图标；统一使用缓慢巡航的星轨徽记，避免不同语言下视觉失衡。
        renderRouteEmblem(g, medallionX, medallionY, medallionRadius,
                visual.index(), elapsedSeconds, alpha, visual.emphasis());

        int footerY = y + h - 16;
        int maxLabelWidth = Math.max(24, w - 12);
        List<FormattedCharSequence> lines = wrapped(
                "card|" + visual.entry().optionId(), label, maxLabelWidth);
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

    /** 通用星轨徽记：中心站点、四向轨道和绕行光点都不依赖候选名称。 */
    private void renderRouteEmblem(GuiGraphics g, int centerX, int centerY, int radius,
                                   int index, float elapsedSeconds, int alpha, float emphasis) {
        int arm = Math.max(3, radius - 3);
        int railAlpha = Math.round(alpha * (0.44f + emphasis * 0.24f));
        g.fill(centerX - arm, centerY, centerX + arm + 1, centerY + 1,
                withAlpha(GOLD, railAlpha));
        g.fill(centerX, centerY - arm, centerX + 1, centerY + arm + 1,
                withAlpha(GOLD, railAlpha));
        drawDiamond(g, centerX, centerY, 2, withAlpha(IVORY, alpha));

        float angle = elapsedSeconds * 1.35f + index * 0.83f;
        int orbit = Math.max(4, radius - 2);
        int lightX = centerX + Math.round(Mth.cos(angle) * orbit);
        int lightY = centerY + Math.round(Mth.sin(angle) * orbit);
        drawDiamond(g, lightX, lightY, emphasis > 0.55f ? 2 : 1,
                withAlpha(GOLD_BRIGHT, alpha));

        float opposite = angle + (float) Math.PI;
        int echoX = centerX + Math.round(Mth.cos(opposite) * orbit);
        int echoY = centerY + Math.round(Mth.sin(opposite) * orbit);
        g.fill(echoX, echoY, echoX + 1, echoY + 1,
                withAlpha(GOLD, Math.round(alpha * 0.55f)));
    }

    private void renderDetails(GuiGraphics g, OptionVotePayload.Entry focused, int candidateCount,
                               float elapsedSeconds) {
        if (mapPhase) {
            renderMapDetails(g, focused, candidateCount, elapsedSeconds);
            return;
        }
        detailBounds = Rect.EMPTY;
        mapPaneSplitX = Integer.MAX_VALUE;
        renderModeDetails(g, focused, candidateCount);
    }

    /** 地图阶段：右侧档案卡 + 连接线 + 底部栏重组。 */
    private void renderMapDetails(GuiGraphics g, OptionVotePayload.Entry focused, int candidateCount,
                                  float elapsedSeconds) {
        float t = easeOutCubic(Mth.clamp(detailProgress, 0.0f, 1.0f));
        if (t < 0.01f) {
            detailBounds = Rect.EMPTY;
            mapPaneSplitX = width;
            renderMapBottomBar(g, focused, candidateCount);
            return;
        }

        MapLayout layout = computeMapLayout();
        int detailW = layout.detailW();
        int detailH = layout.detailH();
        float entrance = easeOutBack(Mth.clamp(detailProgress, 0.0f, 1.0f));
        int detailX = Math.round(Mth.lerp(entrance, width + detailW / 2.0f, layout.detailCenterX()));
        int restingY = layout.detailY();
        int floatY = detailProgress >= 0.98f
                ? Math.round(Mth.sin(elapsedSeconds * 1.45f) * 1.5f) : 0;
        int detailY = restingY + floatY;
        int detailLeft = detailX - detailW / 2;
        detailBounds = new Rect(detailLeft, detailY, detailW, detailH);
        mapPaneSplitX = Math.round(Mth.lerp(t, width,
                (layout.listRight() + layout.detailLeft()) / 2.0f));

        renderConnector(g, focused, detailLeft, detailY, detailH, t);
        renderDetailCard(g, focused, detailLeft, detailY, detailW, detailH, t, elapsedSeconds);
        renderMapBottomBar(g, focused, candidateCount);
    }

    /**
     * 地图阶段响应式布局：右侧档案占主要视觉重量，左侧卡带保留原宽并在分栏边界裁剪。
     * 这样窄屏也不会为了强行并排而把候选卡压成细条。
     */
    private MapLayout computeMapLayout() {
        int horizontalMargin = Math.min(LAYOUT_MARGIN, Math.max(8, width / 24));
        int detailW = Mth.clamp(Math.round(width * DETAIL_W_RATIO), DETAIL_MIN_W, DETAIL_MAX_W);
        detailW = Math.min(detailW, Math.max(96, width - horizontalMargin * 2));
        int detailCenterX = Math.min(Math.round(width * DETAIL_CENTER_RATIO),
                width - horizontalMargin - detailW / 2);
        int detailLeft = detailCenterX - detailW / 2;

        int detailTop = 53;
        int bottomBarY = height - (height < 210 ? 40 : 48) - 6;
        int availableHeight = Math.max(96, bottomBarY - detailTop - 8);
        int preferredHeight = Mth.clamp(Math.round(detailW * 1.52f), DETAIL_MIN_H, DETAIL_MAX_H);
        int detailH = Math.min(preferredHeight, availableHeight);
        int detailY = detailTop + Math.max(0, (availableHeight - detailH) / 2);

        DeckMetrics metrics = deckMetrics();
        int listRight = Math.max(horizontalMargin + metrics.cardWidth(), detailLeft - LAYOUT_GAP);
        int minDeckCenter = horizontalMargin + metrics.cardWidth() / 2;
        int maxDeckCenter = Math.max(minDeckCenter, listRight - metrics.cardWidth() / 2);
        int deckCenterX = Mth.clamp(Math.round(width * DECK_CENTER_RATIO),
                minDeckCenter, maxDeckCenter);
        return new MapLayout(deckCenterX, listRight, detailCenterX, detailLeft,
                detailY, detailW, detailH);
    }

    /** 卡组尺寸随可用高度缩放；展开档案时只改变可见窗口，不改变卡片本身比例。 */
    private DeckMetrics deckMetrics() {
        int detailHeight = height < 210 ? 40 : 48;
        int detailY = height - detailHeight - 6;
        int deckTop = 51;
        int deckBottom = Math.max(deckTop + CARD_MIN_H, detailY - 7);
        int deckHeight = Math.max(CARD_MIN_H, deckBottom - deckTop);
        int cardHeight = Mth.clamp(deckHeight - 4, CARD_MIN_H, CARD_MAX_H);
        int cardWidth = Mth.clamp(Math.round(cardHeight * 0.68f), CARD_MIN_W, CARD_MAX_W);
        // 地图档案展开后改用裁剪形成“卡带穿过窗框”的效果，不再把卡压成细条。
        int spacing = cardWidth + Math.max(8, cardWidth / 9);
        int baseY = deckTop + Math.max(0, (deckHeight - cardHeight) / 2) + 2;
        return new DeckMetrics(cardWidth, cardHeight, spacing, baseY, deckTop, deckHeight);
    }

    private record DeckMetrics(int cardWidth, int cardHeight, int spacing, int baseY,
                               int deckTop, int deckHeight) {}

    private record MapLayout(int deckCenterX, int listRight, int detailCenterX, int detailLeft,
                             int detailY, int detailW, int detailH) {}

    /** 焦点卡底部菱形 → 档案卡左缘的细暗金连接线。 */
    private void renderConnector(GuiGraphics g, OptionVotePayload.Entry focused,
                                 int detailLeft, int detailY, int detailH, float t) {
        if (focused == null || focusedIndex < 0) return;
        Rect card = focusedCardBounds();
        if (card == null) return;
        int startX = card.x() + card.width() / 2;
        int startY = card.y() + card.height() + 5;
        int endX = detailLeft;
        int endY = detailY + detailH / 2;
        int alpha = Math.round(255 * 0.40f * t);
        if (alpha <= 0) return;

        int bendX = Math.max(startX, endX - 16);
        g.hLine(startX, bendX, startY, withAlpha(GOLD, alpha));
        if (endY != startY) {
            g.fill(bendX, Math.min(startY, endY), bendX + 1, Math.max(startY, endY) + 1,
                    withAlpha(GOLD, alpha));
        }
        g.hLine(bendX, endX, endY, withAlpha(GOLD, alpha));
        drawDiamond(g, startX, startY, 3, withAlpha(GOLD, alpha));
        drawDiamond(g, endX, endY, 3, withAlpha(GOLD, alpha));
    }

    private Rect focusedCardBounds() {
        for (CardHitbox hitbox : cardHitboxes) {
            if (hitbox.index() == focusedIndex) return hitbox.bounds();
        }
        return null;
    }

    /** 地图档案卡：预览图 + 名称/编号 + 推荐人数 + 标签 + 介绍。 */
    private void renderDetailCard(GuiGraphics g, OptionVotePayload.Entry focused,
                                  int x, int y, int w, int h, float t, float elapsedSeconds) {
        int alpha = Math.round(255 * t);
        if (alpha <= 0) return;

        float breath = 0.5f + 0.5f * Mth.sin(elapsedSeconds * 1.8f);
        int glowAlpha = Math.round(alpha * (0.10f + breath * 0.08f));
        // 分层投影和低频呼吸光把档案卡从后方背景里“抬”出来。
        fillChamfered(g, x + 7, y + 9, w, h, 5, withAlpha(0xFF000000, alpha * 90 / 255));
        fillChamfered(g, x - 3, y - 3, w + 6, h + 6, 6, withAlpha(GOLD_DARK, glowAlpha));
        g.fillGradient(x, y, x + w, y + h, withAlpha(PANEL, alpha), withAlpha(INK, alpha));
        int border = mixColor(BRONZE, GOLD, 0.18f + breath * 0.20f);
        g.renderOutline(x, y, w, h, withAlpha(border, alpha));
        g.hLine(x + 8, x + w - 8, y + 2,
                withAlpha(GOLD_BRIGHT, Math.round(alpha * (0.48f + breath * 0.22f))));
        renderDetailCorners(g, x, y, w, h, alpha, breath);
        drawDiamond(g, x + w / 2, y + h - 4, breath > 0.72f ? 4 : 3,
                withAlpha(GOLD, alpha));

        // 很淡的竖向扫描光只经过卡体一次，不遮挡文字。
        float bodyScan = (elapsedSeconds * 0.075f) % 1.0f;
        int bodyScanX = x + 4 + Math.round(bodyScan * Math.max(1, w - 9));
        g.fill(bodyScanX, y + 5, bodyScanX + 1, y + h - 5,
                withAlpha(GOLD_BRIGHT, Math.round(alpha * 0.055f)));

        MapVoteProfilePayload.MapProfile profile = OptionVoteState.getProfile(focused.optionId());
        String mapId = focused.optionId();
        int pad = DETAIL_PAD;

        // 预览图固定在档案顶部，下面的信息区域单独滚动。
        int previewW = w - pad * 2;
        int previewH = Math.min(previewW * PREVIEW_ASPECT_H / PREVIEW_ASPECT_W,
                Math.max(46, h / 3));
        int previewX = x + pad;
        int previewY = y + pad;
        renderPreview(g, mapId, profile, previewX, previewY, previewW, previewH, t, elapsedSeconds);

        int bodyTop = previewY + previewH + 9;
        int bodyBottom = y + h - pad - 2;
        int bodyHeight = Math.max(12, bodyBottom - bodyTop);
        int scrollOffset = Math.round(detailScroll);
        float switchT = easeOutCubic(Mth.clamp(detailSwitch, 0.0f, 1.0f));
        int contentAlpha = Math.round(alpha * (0.34f + switchT * 0.66f));
        int contentX = x + pad + Math.round((1.0f - switchT) * 7.0f);
        int contentWidth = Math.max(24, w - pad * 2 - 6);
        int relativeY = 0;

        g.enableScissor(x + pad, bodyTop, x + w - pad, bodyBottom);

        // 名称（米白偏金）
        Component name = OptionVoteTexts.candidateLabel(mapId, focused.displayName());
        g.drawString(font, fit(name, contentWidth), contentX, bodyTop + relativeY - scrollOffset,
                withAlpha(0xFFE6D6B5, contentAlpha), true);
        relativeY += 12;

        // 地图编号
        Component number = OptionVoteTexts.mapNumber(shortOptionId(mapId));
        g.drawString(font, fit(number, contentWidth), contentX, bodyTop + relativeY - scrollOffset,
                withAlpha(TEXT_FAINT, contentAlpha), false);
        relativeY += 14;

        // 推荐人数
        int minP = profile != null ? profile.minPlayers() : 0;
        int maxP = profile != null ? profile.maxPlayers() : 0;
        Component recLabel = OptionVoteTexts.recommendedPlayersLabel();
        Component recValue = (minP <= 0 && maxP <= 0)
                ? OptionVoteTexts.unlimited()
                : Component.literal((minP > 0 ? minP : 1) + " - " + (maxP > 0 ? maxP : 64) + " 人");
        int rowY = bodyTop + relativeY - scrollOffset;
        g.drawString(font, fit(recLabel, Math.max(18, contentWidth - font.width(recValue) - 8)),
                contentX, rowY, withAlpha(TEXT_MUTED, contentAlpha), false);
        g.drawString(font, recValue, x + w - pad - 6 - font.width(recValue), rowY,
                withAlpha(GOLD, contentAlpha), true);
        relativeY += 14;

        // 标签完整保留，超出的部分由右侧独立滚动查看。
        if (profile != null && !profile.tags().isEmpty()) {
            List<String> tags = profile.tags();
            int line = 0;
            int lineX = contentX;
            for (String tag : tags) {
                Component tagComp = Component.literal("◇ " + tag);
                int tagW = font.width(tagComp);
                if (lineX > contentX && lineX + tagW > x + w - pad - 6) {
                    line++;
                    lineX = contentX;
                }
                g.drawString(font, fit(tagComp, contentWidth), lineX,
                        bodyTop + relativeY + line * 10 - scrollOffset,
                        withAlpha(GOLD, contentAlpha), false);
                lineX += tagW + 10;
            }
            relativeY += (line + 1) * 10 + 4;
        }

        // 分隔线
        int separatorY = bodyTop + relativeY - scrollOffset;
        g.hLine(contentX, x + w - pad - 6, separatorY,
                withAlpha(BRONZE, Math.round(contentAlpha * 0.48f)));
        relativeY += 8;

        // 介绍不再截成四行；右侧滚轮可以阅读完整内容。
        if (profile != null && !profile.description().isBlank()) {
            Component description = Component.literal(profile.description());
            List<FormattedCharSequence> lines = wrapped(
                    "description|" + mapId, description, contentWidth);
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(font, lines.get(i), contentX,
                        bodyTop + relativeY + i * 9 - scrollOffset,
                        withAlpha(TEXT, contentAlpha), false);
            }
            relativeY += Math.max(1, lines.size()) * 9;
        } else {
            g.drawString(font, Component.literal("…"), contentX,
                    bodyTop + relativeY - scrollOffset, withAlpha(TEXT_FAINT, contentAlpha), false);
            relativeY += 9;
        }

        g.disableScissor();

        detailScrollMax = Math.max(0.0f, relativeY - bodyHeight);
        detailScrollTarget = Mth.clamp(detailScrollTarget, 0.0f, detailScrollMax);
        detailScroll = Mth.clamp(detailScroll, 0.0f, detailScrollMax);
        renderDetailScrollIndicator(g, x, w, bodyTop, bodyHeight, alpha);
    }

    private List<FormattedCharSequence> wrapped(String key, Component text, int width) {
        String cacheKey = key + '|' + width + '|' + text.getString();
        return wrappedTextCache.computeIfAbsent(cacheKey, ignored -> font.split(text, width));
    }

    private void renderDetailCorners(GuiGraphics g, int x, int y, int w, int h,
                                     int alpha, float breath) {
        int length = 6 + Math.round(breath * 4.0f);
        int color = withAlpha(GOLD_BRIGHT, Math.round(alpha * (0.40f + breath * 0.30f)));
        g.hLine(x + 2, x + 2 + length, y + 5, color);
        g.fill(x + 5, y + 2, x + 6, y + 3 + length, color);
        g.hLine(x + w - 3 - length, x + w - 3, y + h - 6, color);
        g.fill(x + w - 6, y + h - 3 - length, x + w - 5, y + h - 2, color);
    }

    private void renderDetailScrollIndicator(GuiGraphics g, int x, int w,
                                             int bodyTop, int bodyHeight, int alpha) {
        if (detailScrollMax <= 0.5f || bodyHeight <= 12) return;
        int trackX = x + w - 7;
        int thumbHeight = Math.max(12,
                Math.round(bodyHeight * bodyHeight / (bodyHeight + detailScrollMax)));
        int travel = Math.max(1, bodyHeight - thumbHeight);
        int thumbY = bodyTop + Math.round(travel * detailScroll / detailScrollMax);
        g.fill(trackX, bodyTop, trackX + 1, bodyTop + bodyHeight,
                withAlpha(BRONZE, Math.round(alpha * 0.42f)));
        g.fill(trackX - 1, thumbY, trackX + 2, thumbY + thumbHeight,
                withAlpha(GOLD, Math.round(alpha * 0.78f)));
    }

    /** 预览图：懒解码缓存；无图/解码失败回退占位贴图；降亮 + 底部渐隐。 */
    private void renderPreview(GuiGraphics g, String mapId, MapVoteProfilePayload.MapProfile profile,
                               int x, int y, int w, int h, float t, float elapsedSeconds) {
        int alpha = Math.round(255 * t);
        if (alpha <= 0) return;

        MapVotePreviewCache.Decoded decoded = profile != null
                ? MapVotePreviewCache.getOrDecode(mapId, profile.previewBytes()) : null;
        ResourceLocation tex = decoded != null ? decoded.texture() : MapVotePreviewCache.placeholder();
        int texW = decoded != null ? decoded.width() : 512;
        int texH = decoded != null ? decoded.height() : 288;

        // 切换地图时交叉淡入：旧图 alpha (1-switchT) 且 X+5px，新图 alpha switchT 且 X-5px
        float switchT = easeOutCubic(Mth.clamp(detailSwitch, 0.0f, 1.0f));
        if (oldProfile != null && switchT < 1.0f) {
            MapVotePreviewCache.Decoded oldDecoded = MapVotePreviewCache.getOrDecode(
                    oldShowingMapId, oldProfile.previewBytes());
            if (oldDecoded != null) {
                int oldAlpha = Math.round(alpha * (1.0f - switchT));
                if (oldAlpha > 0) {
                    drawPreviewTexture(g, oldDecoded.texture(), x + 5, y, w, h,
                            oldDecoded.width(), oldDecoded.height(), oldAlpha);
                }
            }
        }
        int newAlpha = Math.round(alpha * switchT);
        if (newAlpha > 0) {
            drawPreviewTexture(g, tex, x - Math.round(5 * (1.0f - switchT)), y, w, h, texW, texH, newAlpha);
        }
        g.renderOutline(x, y, w, h, withAlpha(BRONZE, Math.round(alpha * 0.72f)));
        float scan = (elapsedSeconds * 0.16f) % 1.0f;
        int scanX = x + Math.round(scan * Math.max(1, w - 1));
        g.fill(scanX, y + 1, scanX + 1, y + h - 1,
                withAlpha(GOLD_BRIGHT, Math.round(alpha * 0.16f)));
    }

    private void drawPreviewTexture(GuiGraphics g, ResourceLocation tex, int x, int y,
                                    int w, int h, int texW, int texH, int alpha) {
        if (alpha <= 0) return;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.85f, 0.82f, 0.78f, Mth.clamp(alpha / 255.0f, 0.0f, 1.0f));
        g.blit(tex, x, y, 0.0f, 0.0f, w, h, texW, texH);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        // 底部黑棕渐隐遮罩，让图片自然融入卡体
        g.fillGradient(x, y + h - Math.max(8, h / 4), x + w, y + h,
                withAlpha(0x000000, 0), withAlpha(0x000000, Math.round(alpha * 0.70f)));
    }

    /** 地图阶段底部栏：操作提示 + 当前投票状态。 */
    private void renderMapBottomBar(GuiGraphics g, OptionVotePayload.Entry focused, int candidateCount) {
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

        int totalVotes = OptionVoteState.getCandidates().stream()
                .mapToInt(OptionVotePayload.Entry::votes).sum();
        int percent = totalVotes <= 0 ? 0 : Math.round(focused.votes() * 100.0f / totalVotes);
        Component share = OptionVoteTexts.voteShare(focused.votes(), percent);
        g.drawString(font, share, panelX + panelW - 10 - font.width(share),
                panelY + 7, GOLD, true);

        boolean selected = OptionVoteState.isSelected(focused.optionId());
        Component status;
        int statusColor;
        if (selected) {
            status = OptionVoteTexts.selectedStatus();
            statusColor = GOLD_BRIGHT;
        } else {
            String votedId = selectedOptionId();
            if (votedId != null && !votedId.isBlank()) {
                Component votedName = OptionVoteTexts.candidateLabel(votedId, displayNameOf(votedId));
                status = OptionVoteTexts.votedTo(votedName);
                statusColor = TEXT_MUTED;
            } else {
                status = OptionVoteTexts.availableStatus();
                statusColor = TEXT_MUTED;
            }
        }
        g.drawString(font, status, panelX + panelW - 10 - font.width(status),
                panelY + 20, statusColor, false);

        if (panelHeight >= 48) {
            Component hint = fit(OptionVoteTexts.mapControlHint(), Math.max(30, panelW - 20));
            g.drawCenteredString(font, hint, width / 2, panelY + 34, TEXT_FAINT);
        }
    }

    private String selectedOptionId() {
        for (OptionVotePayload.Entry e : OptionVoteState.getCandidates()) {
            if (OptionVoteState.isSelected(e.optionId())) {
                return e.optionId();
            }
        }
        return null;
    }

    private String displayNameOf(String optionId) {
        for (OptionVotePayload.Entry e : OptionVoteState.getCandidates()) {
            if (e.optionId().equals(optionId)) {
                return e.displayName();
            }
        }
        return optionId;
    }

    private void renderModeDetails(GuiGraphics g, OptionVotePayload.Entry focused, int candidateCount) {
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
        if ("map".equals(OptionVoteState.getVoteId())) {
            detailRequested = true;
        }
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
                hideByUser();
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
                boolean overLeftList = !mapPhase || detailProgress < 0.01f || mouseX < mapPaneSplitX;
                if (overLeftList) {
                    for (int i = cardHitboxes.size() - 1; i >= 0; i--) {
                        CardHitbox hitbox = cardHitboxes.get(i);
                        if (hitbox.bounds().contains(mouseX, mouseY)) {
                            castVote(hitbox.index());
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (OptionVoteState.isActive() && scrollY != 0.0) {
            // 地图档案展开后，以左右分区消费滚轮：左侧切换候选，右侧阅读完整档案。
            boolean overRightPane = detailBounds.contains(mouseX, mouseY) || mouseX >= mapPaneSplitX;
            if (mapPhase && detailProgress > 0.08f && overRightPane) {
                detailScrollTarget = Mth.clamp(
                        detailScrollTarget - (float) scrollY * 28.0f,
                        0.0f, detailScrollMax);
                return true;
            }
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                || BlackoutKeyHandler.matchesOpenVoteKey(keyCode, scanCode)) {
            hideByUser();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 玩家主动隐藏：本轮 mode/map 投票页都不再自动弹出，直到手动重开或本轮结束。
     * 与 {@link #onClose()} 区分——阶段切换强制关屏不得写入隐藏偏好。
     */
    public void hideByUser() {
        OptionVoteState.markUiHiddenByUser();
        onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // ESC 走 hideByUser，写入本轮隐藏偏好
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

    private static float easeOutBack(float value) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float shifted = value - 1.0f;
        return 1.0f + c3 * shifted * shifted * shifted + c1 * shifted * shifted;
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
