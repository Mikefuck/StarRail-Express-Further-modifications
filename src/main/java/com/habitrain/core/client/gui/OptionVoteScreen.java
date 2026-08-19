package com.habitrain.core.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.habitrain.core.client.BlackoutKeyHandler;
import com.habitrain.core.client.cache.ClientMapIntroCache;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import io.wifi.starrailexpress.api.AreasSettings;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.client.gui.screen.MapIntroduceScreen;
import io.wifi.starrailexpress.client.gui.screen.MapSpecialRoleLines;
import io.wifi.starrailexpress.client.gui.screen.maprotation.MapIntroDetail;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
    private static final Gson GSON = new Gson();
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
    private static final long DETAIL_OPEN_MILLIS = 320L;      // 档案浮层从底部升起时长
    private static final long DETAIL_CLOSE_MILLIS = 220L;     // 档案浮层回落时长
    private static final long DETAIL_SWITCH_MILLIS = 200L;    // 切换地图时交叉淡入时长
    private static final int DETAIL_MIN_W = 220;
    private static final int DETAIL_MAX_W = 1920;
    private static final int LAYOUT_MARGIN = 24;              // 左右边距
    private static final int LAYOUT_GAP = 24;                 // 紧凑列表与档案浮层最小间隙

    private final Screen parent;
    private final long openedAtMillis = Util.getMillis();
    private final Map<String, Float> cardEmphasis = new HashMap<>();
    private final List<CardVisual> visualBuffer = new ArrayList<>();
    private final List<CardHitbox> hitboxBuffer = new ArrayList<>();
    private final Map<String, List<FormattedCharSequence>> wrappedTextCache = new HashMap<>();
    /** 上次 wrap 时的 Language 实例，用于 F3+T 重载后失效缓存（review L8）。 */
    private net.minecraft.locale.Language wrappedLanguage;

    private List<CardHitbox> cardHitboxes = List.of();
    private Rect closeBounds = Rect.EMPTY;
    private Rect previousBounds = Rect.EMPTY;
    private Rect nextBounds = Rect.EMPTY;
    private Rect detailBounds = Rect.EMPTY;
    private Rect sheetCatalogBounds = Rect.EMPTY;

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
    private int lastMouseX = -1;
    private int lastMouseY = -1;

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
        if ("map".equals(OptionVoteState.getVoteId())) {
            ClientMapIntroCache.requestSyncIfNeeded();
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
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
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

        // 地图投票先展示完整候选卡组；玩家选择/聚焦地图后，卡组才整体左移并让出档案区。
        boolean target = mapPhase && detailRequested;
        if (target != detailOpenTarget) {
            detailOpenTarget = target;
        }
        if (detailOpenTarget && detailProgress < 1.0f) {
            detailProgress = Math.min(1.0f, detailProgress + frameSeconds / (DETAIL_OPEN_MILLIS / 1000.0f));
        } else if (!detailOpenTarget && detailProgress > 0.0f) {
            detailProgress = Math.max(0.0f, detailProgress - frameSeconds / (DETAIL_CLOSE_MILLIS / 1000.0f));
        }

        if (mapPhase && !ClientMapIntroCache.hasData()) {
            ClientMapIntroCache.requestSyncIfNeeded();
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

        // 倒计时修改至左上角阶段信息下方
        g.drawString(font, timer, 12, 22,
                withAlpha(timerColor, timerAlpha), true);

        // 中央标题：地图介绍展开时平滑淡出，避免覆盖在地图介绍页面上面
        float titleFade = 1.0f - easeOutCubic(Mth.clamp(detailProgress, 0.0f, 1.0f));
        int titleAlpha = Math.round(255 * titleFade);
        if (titleAlpha > 0) {
            Component heading = OptionVoteTexts.titleFor(voteId).copy().withStyle(ChatFormatting.BOLD);
            drawScaledCentered(g, heading, width / 2.0f, 8.0f, 1.22f, withAlpha(IVORY, titleAlpha));
            g.drawCenteredString(font, OptionVoteTexts.descriptionFor(voteId),
                    width / 2, 27, withAlpha(TEXT_MUTED, titleAlpha));
        }

        closeBounds = new Rect(Math.max(0, width - 21), 7, 13, 13);
        // 右上角： 按 ESC / <已注册键> 隐藏  [×]
        Component hideTip = OptionVoteTexts.hideHintWithBoundKey();
        int hideTipRight = closeBounds.x() - 7;
        int hideTipX = hideTipRight - font.width(hideTip);
        g.drawString(font, hideTip, hideTipX, 10, TEXT_MUTED, false);
        renderClose(g, mouseX, mouseY);

        MapLayout layout = computeMapLayout();
        float t = easeOutCubic(Mth.clamp(detailProgress, 0.0f, 1.0f));
        int trackX = 12;
        int trackY = 43;
        int maxTrackRight = Math.round(Mth.lerp(t, (float) (width - trackX), (float) layout.listRight()));
        int trackW = Math.max(0, maxTrackRight - trackX);
        if (trackW > 0) {
            g.fill(trackX, trackY, trackX + trackW, trackY + 2, 0x403E322B);
            int filled = Math.round(trackW * displayedCountdown);
            g.fill(trackX, trackY, trackX + filled, trackY + 2, withAlpha(GOLD, 210));
            if (filled > 0) {
                g.fill(Math.max(trackX, trackX + filled - 5), trackY - 1,
                        trackX + filled, trackY + 3, withAlpha(GOLD_BRIGHT, 90));
            }
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

        DeckMetrics metrics = deckMetrics(mapPhase);
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
        // 左箭头放在卡片左侧留白中，避免与当前焦点卡片重叠。
        int cardLeft = layout.deckCenterX() - cardWidth / 2;
        previousBounds = new Rect(Math.max(2, cardLeft - 22), arrowY, 18, 20);
        int nextArrowX = t > 0.001f
                ? Math.max(8, listRight - 20)
                : Math.max(8, listRight - 28);
        nextBounds = new Rect(nextArrowX, arrowY, 20, 20);
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

    /** 地图阶段：大型档案浮层 + 连接线 + 底部栏重组。 */
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
        float entrance = easeOutQuint(Mth.clamp(detailProgress, 0.0f, 1.0f));
        int detailX = layout.detailCenterX();
        int restingY = layout.detailY();
        // 浮层从屏幕底部平滑向上展开，最终覆盖到屏幕顶部，与左侧列表使用同一曲线，避免错位。
        int detailY = Math.round(Mth.lerp(entrance, height + detailH + 24.0f, restingY));
        detailY = Math.max(0, detailY);
        int detailLeft = detailX - detailW / 2;
        detailBounds = new Rect(detailLeft, detailY, detailW, detailH);
        mapPaneSplitX = Math.round(Mth.lerp(t, width,
                (layout.listRight() + layout.detailLeft()) / 2.0f));

        // 浮层盖在当前 GUI 之上，因此先绘制底部栏再绘制档案卡。
        renderMapBottomBar(g, focused, candidateCount);
        renderConnector(g, focused, detailLeft, detailY, detailH, t);
        renderDetailCard(g, focused, detailLeft, detailY, detailW, detailH, t, elapsedSeconds);
    }

    /**
     * 地图阶段响应式布局：展开后左侧只保留“略大于一张卡片”的紧凑窗格，
     * 右侧大型档案浮层从屏幕顶部到底部上下撑满，地图背景以 cover 方式铺满；
     * 列表从右往左靠紧，档案从底部升起。窄屏也不会把候选卡压成细条，
     * 而是让浮层承担主要阅读空间。
     */
    private MapLayout computeMapLayout() {
        // 至少留出 24px，确保左箭头与当前卡片之间不重叠。
        int horizontalMargin = Math.max(24, Math.min(LAYOUT_MARGIN, Math.max(8, width / 24)));
        DeckMetrics metrics = deckMetrics(mapPhase);
        int cardWidth = metrics.cardWidth();

        // 紧凑列表窗格：左边距 + 一张卡片 + 右侧箭头专用空间。
        int listRight = horizontalMargin + cardWidth + 24;
        int deckCenterX = horizontalMargin + cardWidth / 2;

        // 大型档案浮层：从紧凑列表右侧一直延伸到屏幕右边，上下覆盖满。
        int detailLeft = listRight + LAYOUT_GAP;
        int availableRight = Math.max(detailLeft + 1, width - horizontalMargin);
        int availableW = Math.max(1, availableRight - detailLeft);
        int detailW = Math.min(DETAIL_MAX_W, availableW);

        int detailTop = 0;
        int detailBottom = height;
        int detailH = Math.max(1, detailBottom - detailTop);

        int detailCenterX = detailLeft + detailW / 2;
        int detailY = 0;

        return new MapLayout(deckCenterX, listRight, detailCenterX, detailLeft,
                detailY, detailW, detailH);
    }

    /** 卡组尺寸随可用高度缩放；展开档案时只改变可见窗口，不改变卡片本身比例。 */
    private DeckMetrics deckMetrics(boolean mapPhase) {
        int detailHeight = height < 210 ? 40 : 48;
        int detailY = height - detailHeight - 6;
        int deckTop = 51;
        int deckBottom = Math.max(deckTop + CARD_MIN_H, detailY - 7);
        int deckHeight = Math.max(CARD_MIN_H, deckBottom - deckTop);
        int cardHeight = Mth.clamp(deckHeight - 4, CARD_MIN_H, CARD_MAX_H);
        int cardWidth = Mth.clamp(Math.round(cardHeight * 0.68f), CARD_MIN_W, CARD_MAX_W);
        // 地图档案展开后改用裁剪形成“卡带穿过窗框”的效果，不再把卡压成细条。
        // 地图阶段给右侧箭头留出独立空间，避免下一张卡与箭头重叠。
        int spacing = mapPhase
                ? cardWidth + Math.max(28, cardWidth / 5)
                : cardWidth + Math.max(8, cardWidth / 9);
        int baseY = deckTop + Math.max(0, (deckHeight - cardHeight) / 2) + 2;
        return new DeckMetrics(cardWidth, cardHeight, spacing, baseY, deckTop, deckHeight);
    }

    private record DeckMetrics(int cardWidth, int cardHeight, int spacing, int baseY,
                               int deckTop, int deckHeight) {}

    private record MapLayout(int deckCenterX, int listRight, int detailCenterX, int detailLeft,
                             int detailY, int detailW, int detailH) {}

    public record DetailSection(Component title, List<FormattedCharSequence> lines) {}

    /** 信息栏预布局结果：绘制端直接消费，避免二次测量。 */
    private record SheetMetrics(Component name, Component number, Component rec, Component share,
                                boolean selected, boolean oneRow, int percent,
                                List<DetailSection> sections,
                                List<List<Component>> tagRows,
                                int catalogBtnOffsetY, int catalogBtnH,
                                int nameH, int contentH) {}

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

    /**
     * 地图档案卡：顶部为地图大图（图上不叠任何文字信息），下方是一块与卡同宽、
     * 可整体滑动的信息栏——名称/编号/统计/正文/标签全部集中在栏内。向上滚动时
     * 大图随页面一起滑出视野，露出更多介绍正文。整卡仍从底部升起、切图交叉淡入。
     */
    private void renderDetailCard(GuiGraphics g, OptionVotePayload.Entry focused,
                                  int x, int y, int w, int h, float t, float elapsedSeconds) {
        int alpha = Math.round(255 * t);
        if (alpha <= 0) return;

        float breath = 0.5f + 0.5f * Mth.sin(elapsedSeconds * 1.6f);
        int glowAlpha = Math.round(alpha * (0.12f + breath * 0.08f));
        // 分层投影和低频呼吸光先把档案卡从后方背景里“抬”出来。
        fillChamfered(g, x + 7, y + 9, w, h, 5, withAlpha(0xFF000000, alpha * 90 / 255));
        fillChamfered(g, x - 3, y - 3, w + 6, h + 6, 6, withAlpha(GOLD_DARK, glowAlpha));

        MapVoteProfilePayload.MapProfile profile = OptionVoteState.getProfile(focused.optionId());
        String mapId = focused.optionId();
        float switchT = easeOutCubic(Mth.clamp(detailSwitch, 0.0f, 1.0f));
        int contentAlpha = Math.round(alpha * (0.30f + switchT * 0.70f));

        // 页面结构：顶部大图 + 下方信息栏，作为一个整体随滚动上移。
        int heroH = Mth.clamp(Math.round(w * 9.0f / 16.0f), 90, Math.round(h * 0.55f));
        heroH = Math.min(heroH, Math.max(48, h - 72));

        SheetMetrics sheet = computeInfoSheet(focused, profile, mapId, w);
        int sheetH = Math.max(sheet.contentH(), h - heroH);
        detailScrollMax = Math.max(0.0f, heroH + sheetH - h);
        detailScrollTarget = Mth.clamp(detailScrollTarget, 0.0f, detailScrollMax);
        detailScroll = Mth.clamp(detailScroll, 0.0f, detailScrollMax);
        int scrollOffset = Math.round(detailScroll);

        // 卡身底色：大图滑出后信息栏上方不会露出透明区域。
        g.fill(x, y, x + w, y + h, withAlpha(PANEL, Math.round(alpha * 0.96f)));

        // 图 + 信息栏整体上移，超出卡身的部分裁掉。
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        int heroY = y - scrollOffset;
        renderMapBackground(g, mapId, profile, x, heroY, w, heroH, t, elapsedSeconds);
        g.fillGradient(x, heroY + heroH - 18, x + w, heroY + heroH,
                withAlpha(0xFF221917, 0), withAlpha(0xFF221917, Math.round(alpha * 0.96f)));

        renderInfoSheet(g, x, heroY + heroH, w, sheet, contentAlpha, alpha);
        g.disableScissor();

        // 边框与四角装饰压在内容之上。
        int border = mixColor(BRONZE, GOLD, 0.18f + breath * 0.20f);
        g.renderOutline(x, y, w, h, withAlpha(border, alpha));
        g.hLine(x + 8, x + w - 8, y + 2,
                withAlpha(GOLD_BRIGHT, Math.round(alpha * (0.48f + breath * 0.22f))));
        renderDetailCorners(g, x, y, w, h, alpha, breath);

        // 整页滚动条沿卡片右缘。
        renderDetailScrollIndicator(g, x + w - 10, y + 8, h - 16, alpha);
    }

    /** 信息栏预布局：一次算好所有块的内容与总高，绘制端零重复计算。 */
    private SheetMetrics computeInfoSheet(OptionVotePayload.Entry focused,
                                          MapVoteProfilePayload.MapProfile profile, String mapId,
                                          int w) {
        int innerX = 14;
        int innerW = Math.max(24, w - innerX * 2);
        float nameScale = 1.25f;

        Component name = fit(OptionVoteTexts.candidateLabel(mapId, focused.displayName()),
                Math.max(20, Math.round(innerW / nameScale)));
        int nameH = Math.round(font.lineHeight * nameScale);
        Component number = fit(OptionVoteTexts.mapNumber(shortOptionId(mapId)), innerW);

        // 从客户端缓存读取上游地图介绍数据
        JsonObject json = ClientMapIntroCache.getMapJson(mapId);
        MapIntroSyncPayload.VoteMap voteMap = ClientMapIntroCache.getVoteMap(mapId);
        MapIntroDetail.SpecialSets specialSets = ClientMapIntroCache.getSpecialSets();

        // 推荐人数：优先从 voteMap 或 profile 中读取
        int minP = profile != null && profile.minPlayers() > 0 ? profile.minPlayers() : (voteMap != null ? voteMap.minCount() : 0);
        int maxP = profile != null && profile.maxPlayers() > 0 ? profile.maxPlayers() : (voteMap != null ? voteMap.maxCount() : 0);
        Component recValue = (minP <= 0 && maxP <= 0)
                ? OptionVoteTexts.unlimited()
                : Component.literal((minP > 0 ? minP : 1) + " - " + (maxP > 0 ? maxP : 64) + " 人");
        Component rec = OptionVoteTexts.recommendedPlayersLabel().copy()
                .append("  ").append(recValue);

        int totalVotes = OptionVoteState.getCandidates().stream()
                .mapToInt(OptionVotePayload.Entry::votes).sum();
        int percent = totalVotes <= 0 ? 0 : Math.round(focused.votes() * 100.0f / totalVotes);
        Component share = OptionVoteTexts.voteShare(focused.votes(), percent);
        boolean selected = OptionVoteState.isSelected(focused.optionId());
        boolean oneRow = font.width(rec) + font.width(share) + (selected ? 8 : 0) + 16 <= innerW;

        int lineHeight = font.lineHeight + 1;
        int sectionGap = 10;
        int sectionTitleH = font.lineHeight + 4;

        List<DetailSection> sections = new ArrayList<>();

        // 1) 投票配置板块
        if (voteMap != null) {
            List<FormattedCharSequence> voteLines = new ArrayList<>();
            addSectionLine(voteLines, "map_intro.vote.display_name", focused.displayName().isBlank() ? voteMap.displayName() : focused.displayName(), innerW);
            addSectionLine(voteLines, "map_intro.vote.min_count", Component.translatable(
                    voteMap.minCount() <= 0 ? "map_intro.vote.no_min_count" : "map_intro.vote.count_value", voteMap.minCount()), innerW);
            addSectionLine(voteLines, "map_intro.vote.max_count", Component.translatable(
                    voteMap.maxCount() <= 0 ? "map_intro.vote.no_max_count" : "map_intro.vote.count_value", voteMap.maxCount()), innerW);
            addSectionLine(voteLines, voteMap.canSelect() ? "map_intro.vote.can_select.true" : "map_intro.vote.can_select.false", innerW);
            addSectionLine(voteLines, "map_intro.vote.game_modes", MapIntroDetail.gameModesText(voteMap.gameModes()), innerW);
            sections.add(new DetailSection(Component.translatable("map_intro.section.vote_config").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), voteLines));
        }

        // 2) 特定地图刷新职业板块
        List<Component> specialComponents = MapSpecialRoleLines.build(mapId,
                specialSets.bag(), specialSets.police(), specialSets.underwater(),
                specialSets.air(), specialSets.trap(), specialSets.horse(), json);
        List<FormattedCharSequence> specialLines = new ArrayList<>();
        if (specialComponents.isEmpty()) {
            specialLines.addAll(font.split(Component.translatable("map_intro.special.none").withStyle(ChatFormatting.GRAY), innerW));
        } else {
            for (Component spec : specialComponents) {
                specialLines.addAll(font.split(spec, innerW));
            }
        }
        sections.add(new DetailSection(Component.translatable("map_intro.section.special_roles").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), specialLines));

        // 3) 地图介绍/描述
        String desc = profile != null && !profile.description().isBlank() ? profile.description() : "";
        if (!desc.isBlank()) {
            List<FormattedCharSequence> descLines = wrapped("desc|" + mapId, Component.literal(desc), Math.min(innerW, 520));
            sections.add(new DetailSection(Component.translatable("map_intro.section.info").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), descLines));
        }

        // 4) 地图属性板块
        if (json != null) {
            List<FormattedCharSequence> propLines = buildPropertiesLines(json, innerW);
            if (!propLines.isEmpty()) {
                sections.add(new DetailSection(Component.translatable("map_intro.section.properties").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), propLines));
            }
        }

        // 标签胶囊
        List<String> tags = profile != null && profile.tags() != null ? profile.tags() : List.of();
        List<List<Component>> tagRows = layoutTagRows(tags, innerW);
        int chipH = font.lineHeight + 4;
        int tagsH = tagRows.isEmpty() ? 0 : tagRows.size() * chipH + (tagRows.size() - 1) * 4;

        // 计算各部分总高度
        int runningH = 12 + nameH + 4 + font.lineHeight + 6
                + (oneRow ? 1 : 2) * font.lineHeight + 4
                + 8; // 统计行 + 比例条

        for (DetailSection section : sections) {
            runningH += sectionGap + sectionTitleH + section.lines().size() * lineHeight;
        }

        if (tagsH > 0) {
            runningH += 10 + tagsH;
        }

        int catalogBtnH = 22;
        int catalogBtnOffsetY = runningH + 12;
        runningH += 12 + catalogBtnH + 14;

        return new SheetMetrics(name, number, rec, share, selected, oneRow, percent,
                sections, tagRows, catalogBtnOffsetY, catalogBtnH, nameH, runningH);
    }

    /** 图片下方的可滑动信息栏：名称 → 编号 → 统计行 + 比例条 → 各配置板块 → 标签胶囊 → 完整图鉴按钮。 */
    private void renderInfoSheet(GuiGraphics g, int x, int sheetY, int w,
                                 SheetMetrics m, int contentAlpha, int alpha) {
        if (alpha <= 0) return;
        int innerX = x + 14;
        int innerRight = x + w - 14;
        int innerW = innerRight - innerX;

        // 名称（放大）→ 编号。
        g.pose().pushPose();
        g.pose().translate(innerX, sheetY + 12, 0.0f);
        g.pose().scale(1.25f, 1.25f, 1.0f);
        g.drawString(font, m.name(), 0, 0, withAlpha(0xFFE6D6B5, contentAlpha), true);
        g.pose().popPose();
        int cy = sheetY + 12 + m.nameH() + 4;
        g.drawString(font, m.number(), innerX, cy, withAlpha(TEXT_FAINT, contentAlpha), false);
        cy += font.lineHeight + 6;

        // 统计行：推荐人数（左）+ 票数占比（右，已投带菱形徽记），放不下拆两行。
        g.drawString(font, m.rec(), innerX, cy, withAlpha(GOLD, contentAlpha), true);
        if (m.oneRow()) {
            int shareX = innerRight - font.width(m.share());
            if (m.selected()) {
                drawDiamond(g, shareX - 8, cy + font.lineHeight / 2, 2,
                        withAlpha(GOLD_BRIGHT, contentAlpha));
            }
            g.drawString(font, m.share(), shareX, cy, withAlpha(GOLD_BRIGHT, contentAlpha), true);
            cy += font.lineHeight + 4;
        } else {
            cy += font.lineHeight + 2;
            if (m.selected()) {
                drawDiamond(g, innerX + 3, cy + font.lineHeight / 2, 2,
                        withAlpha(GOLD_BRIGHT, contentAlpha));
            }
            g.drawString(font, m.share(), m.selected() ? innerX + 8 : innerX, cy,
                    withAlpha(GOLD_BRIGHT, contentAlpha), true);
            cy += font.lineHeight + 4;
        }

        // 迷你票数比例条。
        g.fill(innerX, cy, innerRight, cy + 2, withAlpha(BRONZE, Math.round(contentAlpha * 0.60f)));
        int fillW = Math.round((innerRight - innerX) * Mth.clamp(m.percent() / 100.0f, 0.0f, 1.0f));
        if (fillW > 0) {
            g.fill(innerX, cy, innerX + fillW, cy + 2, withAlpha(GOLD, Math.round(contentAlpha * 0.95f)));
        }
        cy += 2 + 8;

        int lineHeight = font.lineHeight + 1;

        // 各详细板块（投票配置、特殊职业、描述、地图属性）
        for (DetailSection section : m.sections()) {
            g.hLine(innerX, innerRight, cy, withAlpha(BRONZE, Math.round(contentAlpha * 0.50f)));
            cy += 8;
            g.drawString(font, section.title(), innerX, cy, withAlpha(0xFFFFE8C0, contentAlpha), true);
            cy += font.lineHeight + 4;
            for (FormattedCharSequence line : section.lines()) {
                g.drawString(font, line, innerX, cy, withAlpha(TEXT, contentAlpha), false);
                cy += lineHeight;
            }
            cy += 2;
        }

        // 标签胶囊。
        int chipH = font.lineHeight + 4;
        if (!m.tagRows().isEmpty()) {
            cy += 6;
            for (List<Component> row : m.tagRows()) {
                int chipX = innerX;
                for (Component label : row) {
                    int chipW = font.width(label) + 8;
                    g.fill(chipX, cy, chipX + chipW, cy + chipH,
                            withAlpha(0xFF1C1611, Math.round(contentAlpha * 0.85f)));
                    g.renderOutline(chipX, cy, chipW, chipH,
                            withAlpha(BRONZE, Math.round(contentAlpha * 0.55f)));
                    g.drawString(font, label, chipX + 4, cy + 2,
                            withAlpha(GOLD, contentAlpha), false);
                    chipX += chipW + 4;
                }
                cy += chipH + 4;
            }
        }

        // 底部「查看完整地图介绍 (方块/机制图鉴)」按钮
        int btnW = Math.min(innerW, 260);
        int btnH = m.catalogBtnH();
        int btnX = innerX + (innerW - btnW) / 2;
        int btnY = sheetY + m.catalogBtnOffsetY();
        sheetCatalogBounds = new Rect(btnX, btnY, btnW, btnH);
        boolean btnHovered = sheetCatalogBounds.contains(lastMouseX, lastMouseY);

        g.fill(btnX, btnY, btnX + btnW, btnY + btnH,
                withAlpha(btnHovered ? 0xDD3A2818 : 0xAA221712, contentAlpha));
        g.renderOutline(btnX, btnY, btnW, btnH,
                withAlpha(btnHovered ? GOLD_BRIGHT : BRONZE, contentAlpha));
        Component btnText = Component.literal("📖 ").append(Component.translatable("map_intro.title")).append(" (方块/机制图鉴) →");
        g.drawCenteredString(font, fit(btnText, btnW - 12), btnX + btnW / 2, btnY + 7,
                withAlpha(btnHovered ? IVORY : GOLD, contentAlpha));
    }

    private void addSectionLine(List<FormattedCharSequence> lines, String key, Object value, int wrapW) {
        lines.addAll(font.split(Component.translatable(key, value), wrapW));
    }

    private void addSectionLine(List<FormattedCharSequence> lines, String key, int wrapW) {
        lines.addAll(font.split(Component.translatable(key), wrapW));
    }

    private List<FormattedCharSequence> buildPropertiesLines(JsonObject json, int wrapW) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (json == null) return lines;

        addSectionLine(lines, "map_intro.property.room_count", intValue(json, "roomCount", 1), wrapW);
        addNameSet(lines, json, "disabledTasks", "map_intro.property.disabled_tasks", v -> taskName(v, false), wrapW);
        addNameSet(lines, json, "disabledRoles", "map_intro.property.disabled_roles", OptionVoteScreen::roleName, wrapW);
        addNameSet(lines, json, "enableSceneTask", "map_intro.property.scene_tasks", v -> taskName(v, true), wrapW);

        if (boolValue(json, "minigameQuestEnabled", false)) {
            addSectionLine(lines, "map_intro.property.minigame_quest", wrapW);
        }
        if (meetingBoolValue(json, "meetingEnabled", false)) {
            addSectionLine(lines, "map_intro.property.meeting_enabled", wrapW);
        }
        if (meetingBoolValue(json, "meetingVoteEnabled", false)) {
            addSectionLine(lines, "map_intro.property.meeting_vote_enabled", wrapW);
        }
        if (meetingBoolValue(json, "bellMeetingEnabled", false)) {
            addSectionLine(lines, "map_intro.property.bell_meeting_enabled", wrapW);
        }

        String status = stringValue(json, "mapStatusBar", "NONE");
        if (!status.equalsIgnoreCase("NONE") && !status.isBlank()) {
            addSectionLine(lines, "map_intro.property.status_bar", statusName(status), wrapW);
        }

        if (json.has("settings")) {
            try {
                AreasSettings areasSettings = GSON.fromJson(json.get("settings"), AreasSettings.class);
                boolean canSwim = areasSettings.canSimpleSwim && areasSettings.canUnderWater && areasSettings.allowInDeepWater
                        && (areasSettings.canJump || areasSettings.canSwim);
                addSectionLine(lines, canSwim ? "map_intro.property.can_swim.true" : "map_intro.property.can_swim.false", wrapW);
                if (areasSettings.enableOxygenDrowning) {
                    addSectionLine(lines, "map_intro.property.oxygen_drowning", wrapW);
                }
                addSectionLine(lines, areasSettings.canJump ? "map_intro.property.can_jump.true" : "map_intro.property.can_jump.false", wrapW);
                if (areasSettings.snowEnabled) addSectionLine(lines, "map_intro.property.snow", wrapW);
                if (areasSettings.sandEnabled) addSectionLine(lines, "map_intro.property.sand", wrapW);
                if (!areasSettings.fogEnabled) addSectionLine(lines, "map_intro.property.no_fog", wrapW);
                addSectionLine(lines, "map_intro.property.fog_end", trimNumber(areasSettings.fogEnd), wrapW);
                if (areasSettings.weather != null && !areasSettings.weather.equals(AreasSettings.MinecraftWeather.clear)) {
                    String wName = areasSettings.weather.name();
                    addSectionLine(lines, "map_intro.property.weather", Component.translatableWithFallback("map_intro.weather." + wName.toLowerCase(Locale.ROOT), wName), wrapW);
                }
                if (Math.abs(areasSettings.gravityModifier - 0.08D) > 0.0001D) {
                    addSectionLine(lines, "map_intro.property.gravity", Component.translatable(areasSettings.gravityModifier < 0.08D ? "map_intro.gravity.low" : "map_intro.gravity.high"), wrapW);
                }
                addEffects(lines, areasSettings, wrapW);
                addInitialItems(lines, areasSettings, wrapW);
                if (areasSettings.time != 18000L) {
                    addSectionLine(lines, "map_intro.property.time", Component.translatable(timeName(areasSettings.time)), wrapW);
                }
                if (areasSettings.daylightCycle) addSectionLine(lines, "map_intro.property.daylight_cycle", wrapW);
                if (areasSettings.weatherCycle) addSectionLine(lines, "map_intro.property.weather_cycle", wrapW);
            } catch (Exception ignored) {
            }
        } else {
            addSectionLine(lines, boolValue(json, "canSwim", false) ? "map_intro.property.can_swim.true" : "map_intro.property.can_swim.false", wrapW);
            if (boolValue(json, "enableOxygenDrowning", false)) {
                addSectionLine(lines, "map_intro.property.oxygen_drowning", wrapW);
            }
            addSectionLine(lines, boolValue(json, "canJump", false) ? "map_intro.property.can_jump.true" : "map_intro.property.can_jump.false", wrapW);
            if (boolValue(json, "snowEnabled", false)) addSectionLine(lines, "map_intro.property.snow", wrapW);
            if (boolValue(json, "sandEnabled", false)) addSectionLine(lines, "map_intro.property.sand", wrapW);
            if (!boolValue(json, "fogEnabled", true)) addSectionLine(lines, "map_intro.property.no_fog", wrapW);
            addSectionLine(lines, "map_intro.property.fog_end", trimNumber(doubleValue(json, "fogEnd", 200.0D)), wrapW);
            String weather = stringValue(json, "weather", "clear");
            if (!weather.equalsIgnoreCase("clear")) {
                addSectionLine(lines, "map_intro.property.weather", Component.translatableWithFallback("map_intro.weather." + weather.toLowerCase(Locale.ROOT), weather), wrapW);
            }
            double gravity = doubleValue(json, "gravity", 0.08D);
            if (Math.abs(gravity - 0.08D) > 0.0001D) {
                addSectionLine(lines, "map_intro.property.gravity", Component.translatable(gravity < 0.08D ? "map_intro.gravity.low" : "map_intro.gravity.high"), wrapW);
            }
            addEffects(lines, json, wrapW);
            addInitialItems(lines, json, wrapW);
            long time = longValue(json, "time", 18000L);
            if (time != 18000L) {
                addSectionLine(lines, "map_intro.property.time", Component.translatable(timeName(time)), wrapW);
            }
            if (boolValue(json, "daylightCycle", false)) addSectionLine(lines, "map_intro.property.daylight_cycle", wrapW);
            if (boolValue(json, "weatherCycle", false)) addSectionLine(lines, "map_intro.property.weather_cycle", wrapW);
        }

        return lines;
    }

    private interface NameMapper {
        String apply(String rawId);
    }

    private void addNameSet(List<FormattedCharSequence> lines, JsonObject json, String key, String labelKey,
                            NameMapper mapper, int wrapW) {
        List<String> names = new ArrayList<>();
        for (JsonElement element : arrayOf(json, key)) {
            if (element.isJsonPrimitive()) {
                names.add(mapper.apply(element.getAsString()));
            }
        }
        if (!names.isEmpty()) {
            addSectionLine(lines, labelKey, String.join(", ", names), wrapW);
        }
    }

    private void addEffects(List<FormattedCharSequence> lines, AreasSettings areasSettings, int wrapW) {
        if (areasSettings.mobEffects == null) return;
        List<String> parts = new ArrayList<>();
        for (String element : areasSettings.mobEffects) {
            if (element == null || element.isBlank()) continue;
            String[] split = element.split(",", 2);
            int level = split.length > 1 ? parseInt(split[1], 1) : 1;
            String name = split[0];
            ResourceLocation id = ResourceLocation.tryParse(split[0]);
            if (id != null) {
                var effect = BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null);
                if (effect != null) {
                    name = Component.translatable(effect.value().getDescriptionId()).getString();
                }
            }
            parts.add(Component.translatable("map_intro.effect.entry", name, level).getString());
        }
        if (!parts.isEmpty()) {
            addSectionLine(lines, "map_intro.property.effects", String.join(", ", parts), wrapW);
        }
    }

    private void addEffects(List<FormattedCharSequence> lines, JsonObject json, int wrapW) {
        List<String> parts = new ArrayList<>();
        for (JsonElement element : arrayOf(json, "effect")) {
            if (!element.isJsonPrimitive()) continue;
            String[] split = element.getAsString().split(",", 2);
            int level = split.length > 1 ? parseInt(split[1], 1) : 1;
            String name = split[0];
            ResourceLocation id = ResourceLocation.tryParse(split[0]);
            if (id != null) {
                var effect = BuiltInRegistries.MOB_EFFECT.getHolder(id).orElse(null);
                if (effect != null) {
                    name = Component.translatable(effect.value().getDescriptionId()).getString();
                }
            }
            parts.add(Component.translatable("map_intro.effect.entry", name, level).getString());
        }
        if (!parts.isEmpty()) {
            addSectionLine(lines, "map_intro.property.effects", String.join(", ", parts), wrapW);
        }
    }

    private void addInitialItems(List<FormattedCharSequence> lines, AreasSettings areasSettings, int wrapW) {
        if (areasSettings.initialItems == null) return;
        List<String> parts = new ArrayList<>();
        for (String element : areasSettings.initialItems) {
            if (element == null || element.isBlank()) continue;
            String[] split = element.split("[;,]", 2);
            ResourceLocation id = ResourceLocation.tryParse(split[0]);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) continue;
            int count = split.length > 1 ? parseInt(split[1], 1) : 1;
            String name = item.getDescription().getString();
            parts.add(count > 1 ? Component.translatable("map_intro.item.entry", name, count).getString() : name);
        }
        if (!parts.isEmpty()) {
            addSectionLine(lines, "map_intro.property.initial_items", String.join(", ", parts), wrapW);
        }
    }

    private void addInitialItems(List<FormattedCharSequence> lines, JsonObject json, int wrapW) {
        List<String> parts = new ArrayList<>();
        for (JsonElement element : arrayOf(json, "initialItems")) {
            if (!element.isJsonPrimitive()) continue;
            String[] split = element.getAsString().split("[;,]", 2);
            ResourceLocation id = ResourceLocation.tryParse(split[0]);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) continue;
            int count = split.length > 1 ? parseInt(split[1], 1) : 1;
            String name = item.getDescription().getString();
            parts.add(count > 1 ? Component.translatable("map_intro.item.entry", name, count).getString() : name);
        }
        if (!parts.isEmpty()) {
            addSectionLine(lines, "map_intro.property.initial_items", String.join(", ", parts), wrapW);
        }
    }

    private static String taskName(String id, boolean scene) {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (scene) {
            return Component.translatableWithFallback("scene_task.noellesroles." + normalized,
                    Component.translatableWithFallback("task." + normalized, id).getString()).getString();
        }
        if ("raed_book".equals(normalized)) {
            normalized = "read_book";
        }
        return Component.translatableWithFallback("task." + normalized, id).getString();
    }

    private static String roleName(String id) {
        SRERole role = null;
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location != null) {
            role = TMMRoles.getRole(location);
        }
        if (role == null) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            for (SRERole candidate : TMMRoles.ROLES.values()) {
                if (candidate.identifier().getPath().equals(path)) {
                    role = candidate;
                    break;
                }
            }
        }
        return role == null ? id : role.getName().getString();
    }

    private static String statusName(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "COLD", "WARM", "WARMTH" -> Component.translatable("map_intro.status.warmth").getString();
            case "THIRST" -> Component.translatable("map_intro.status.thirst").getString();
            case "HUNGER" -> Component.translatable("map_intro.status.hunger").getString();
            case "POLLUTION" -> Component.translatable("map_intro.status.pollution").getString();
            default -> value;
        };
    }

    private static String timeName(long time) {
        long t = Math.floorMod(time, 24000L);
        long[] points = { 6000L, 12000L, 18000L, 23000L };
        String[] keys = { "map_intro.time.noon", "map_intro.time.dusk", "map_intro.time.midnight", "map_intro.time.dawn" };
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < points.length; i++) {
            long dist = Math.min(Math.abs(t - points[i]), 24000L - Math.abs(t - points[i]));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return keys[best];
    }

    private static List<JsonElement> arrayOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        List<JsonElement> result = new ArrayList<>();
        json.getAsJsonArray(key).forEach(result::add);
        return result;
    }

    private static boolean isNumber(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isNumber();
    }

    private static int intValue(JsonObject json, String key, int fallback) {
        return isNumber(json, key) ? json.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject json, String key, long fallback) {
        return isNumber(json, key) ? json.get(key).getAsLong() : fallback;
    }

    private static double doubleValue(JsonObject json, String key, double fallback) {
        return isNumber(json, key) ? json.get(key).getAsDouble() : fallback;
    }

    private static boolean boolValue(JsonObject json, String key, boolean fallback) {
        if (json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean()) {
            return json.get(key).getAsBoolean();
        }
        return fallback;
    }

    private static String stringValue(JsonObject json, String key, String fallback) {
        if (json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isString()) {
            return json.get(key).getAsString();
        }
        return fallback;
    }

    private static boolean meetingBoolValue(JsonObject json, String key, boolean fallback) {
        if (json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean()) {
            return json.get(key).getAsBoolean();
        }
        if (json != null && json.has("settings") && json.get("settings").isJsonObject()) {
            return boolValue(json.getAsJsonObject("settings"), key, fallback);
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String trimNumber(double value) {
        return Math.abs(value - Math.rint(value)) < 0.0001D
                ? String.valueOf((int) Math.rint(value))
                : String.format(Locale.ROOT, "%.2f", value);
    }

    private void openMapIntroduceScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new MapIntroduceScreen(this));
            playUiSound(1.0f);
        }
    }

    /** 标签胶囊按宽度贪心换行，最多 3 行（8 个短标签通常 1-2 行），超出丢弃。 */
    private List<List<Component>> layoutTagRows(List<String> tags, int maxW) {
        if (tags.isEmpty() || maxW < 30) return List.of();
        List<List<Component>> rows = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        int rowW = 0;
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            Component label = fit(Component.literal(tag), Math.max(20, maxW - 8));
            int chipW = font.width(label) + 8;
            if (!current.isEmpty() && rowW + 4 + chipW > maxW) {
                rows.add(current);
                current = new ArrayList<>();
                rowW = 0;
            }
            current.add(label);
            rowW += chipW + 4;
        }
        if (!current.isEmpty()) rows.add(current);
        return rows.size() > 3 ? new ArrayList<>(rows.subList(0, 3)) : rows;
    }

    private List<FormattedCharSequence> wrapped(String key, Component text, int width) {
        // 语言/资源重载（F3+T）会替换 Language 实例：以实例身份检测变化并清空，
        // 避免旧语言文本滞留；同时给缓存一个上限，防止反复缩放时按宽度累积
        // （review L8）。
        net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
        if (language != wrappedLanguage) {
            wrappedLanguage = language;
            wrappedTextCache.clear();
        }
        if (wrappedTextCache.size() > 512) {
            wrappedTextCache.clear();
        }
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

    private void renderDetailScrollIndicator(GuiGraphics g, int x,
                                             int bodyTop, int bodyHeight, int alpha) {
        if (detailScrollMax <= 0.5f || bodyHeight <= 12) return;
        int trackX = x;
        int thumbHeight = Math.max(14,
                Math.round(bodyHeight * bodyHeight / (bodyHeight + detailScrollMax)));
        int travel = Math.max(1, bodyHeight - thumbHeight);
        int thumbY = bodyTop + Math.round(travel * detailScroll / detailScrollMax);
        g.fill(trackX, bodyTop, trackX + 2, bodyTop + bodyHeight,
                withAlpha(BRONZE, Math.round(alpha * 0.50f)));
        g.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight,
                withAlpha(GOLD, Math.round(alpha * 0.85f)));
    }

    /**
     * 地图背景：懒解码缓存；无图/解码失败回退占位贴图；整张卡作为 16:9 背景铺满，
     * 切换地图时旧图/新图交叉淡入。
     */
    private void renderMapBackground(GuiGraphics g, String mapId,
                                     MapVoteProfilePayload.MapProfile profile,
                                     int x, int y, int w, int h, float t, float elapsedSeconds) {
        int alpha = Math.round(255 * t);
        if (alpha <= 0 || w <= 0 || h <= 0) return;

        MapVotePreviewCache.Decoded decoded = profile != null
                ? MapVotePreviewCache.getOrDecode(mapId, profile.previewBytes()) : null;
        ResourceLocation tex = decoded != null ? decoded.texture() : MapVotePreviewCache.placeholder();
        int texW = decoded != null ? decoded.width() : 512;
        int texH = decoded != null ? decoded.height() : 288;

        float switchT = easeOutCubic(Mth.clamp(detailSwitch, 0.0f, 1.0f));
        if (oldProfile != null && switchT < 1.0f) {
            MapVotePreviewCache.Decoded oldDecoded = MapVotePreviewCache.getOrDecode(
                    oldShowingMapId, oldProfile.previewBytes());
            if (oldDecoded != null) {
                int oldAlpha = Math.round(alpha * (1.0f - switchT));
                if (oldAlpha > 0) {
                    drawPreviewTextureCover(g, oldDecoded.texture(), x, y, w, h,
                            oldDecoded.width(), oldDecoded.height(), oldAlpha);
                }
            }
        }
        // 首次打开没有旧图时直接显示新图，避免交叉淡入起始帧空白。
        int newAlpha = Math.round(alpha * (oldProfile == null ? 1.0f : switchT));
        if (newAlpha > 0) {
            drawPreviewTextureCover(g, tex, x, y, w, h, texW, texH, newAlpha);
        }

        // 极淡的扫描光经过背景，不遮挡文字。
        float scan = (elapsedSeconds * 0.08f) % 1.0f;
        int scanX = x + Math.round(scan * Math.max(1, w - 1));
        g.fill(scanX, y + 1, scanX + 1, y + h - 1,
                withAlpha(GOLD_BRIGHT, Math.round(alpha * 0.05f)));
    }

    /** 把地图纹理以 cover 方式铺满目标矩形，保持原图比例并裁掉多余部分。 */
    private void drawPreviewTextureCover(GuiGraphics g, ResourceLocation tex, int x, int y,
                                         int w, int h, int texW, int texH, int alpha) {
        if (alpha <= 0 || w <= 0 || h <= 0 || texW <= 0 || texH <= 0) return;
        float scale = Math.max(w / (float) texW, h / (float) texH);
        int srcW = Math.max(1, Math.round(w / scale));
        int srcH = Math.max(1, Math.round(h / scale));
        int srcX = (texW - srcW) / 2;
        int srcY = (texH - srcH) / 2;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.82f, 0.79f, 0.75f, Mth.clamp(alpha / 255.0f, 0.0f, 1.0f));
        g.blit(tex, x, y, w, h, srcX, srcY, srcW, srcH, texW, texH);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    /**
     * 地图阶段底部栏：介绍页未展开时显示右侧票数/状态；介绍页展开后由浮层内
     * 的右下角信息接管，这里不再重复绘制，避免“1票 100%”同时出现两个。
     */
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

        if (focused == null || detailProgress > 0.01f) return;

        int totalVotes = OptionVoteState.getCandidates().stream()
                .mapToInt(OptionVotePayload.Entry::votes).sum();
        int percent = totalVotes <= 0 ? 0 : Math.round(focused.votes() * 100.0f / totalVotes);
        Component share = OptionVoteTexts.voteShare(focused.votes(), percent);

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

        g.drawString(font, share, panelX + panelW - 10 - font.width(share),
                panelY + 7, GOLD, true);
        g.drawString(font, status, panelX + panelW - 10 - font.width(status),
                panelY + 20, statusColor, false);
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
        if (sound && changed) {
            playUiSound(1.25f);
            if ("map".equals(OptionVoteState.getVoteId())) {
                detailRequested = true;
            }
        }
    }

    private void castVote(int index) {
        if (!OptionVoteState.isActive()) return;
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        if (index < 0 || index >= candidates.size()) return;

        OptionVotePayload.Entry entry = candidates.get(index);
        if ("map".equals(OptionVoteState.getVoteId())) {
            // 点击左侧地图卡：当前展开则收起，当前收起则展开。
            detailRequested = !detailRequested;
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
            if (mapPhase && detailProgress > 0.08f && detailBounds.contains(mouseX, mouseY)
                    && sheetCatalogBounds.contains(mouseX, mouseY)) {
                openMapIntroduceScreen();
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
                    // 点击左侧列表的空白区域：介绍页展开时直接收起。
                    if (mapPhase && detailProgress > 0.01f && mouseX < mapPaneSplitX
                            && mouseY >= 46 && mouseY <= height - 56) {
                        detailRequested = false;
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

    private static float easeOutQuint(float value) {
        float t = Mth.clamp(value, 0.0f, 1.0f);
        float inverse = 1.0f - t;
        return 1.0f - inverse * inverse * inverse * inverse * inverse;
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
